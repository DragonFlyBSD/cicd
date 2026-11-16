// Run from the repository root: groovy tests/release-workflow.groovy
// Execute the actual pipeline's Git stages against disposable local repositories.
// This harness supplies Pipeline steps; it does not emulate Jenkins CPS or GPG.
def pipelineFile = new File('jenkins/Jenkinsfile.release-git').canonicalFile
def root = File.createTempDir('release-workflow-', '')
def gitEnvironment = [GIT_CONFIG_NOSYSTEM:'1', GIT_CONFIG_GLOBAL:'/dev/null']
def run = { File directory, List<String> command, Map extra = [:] ->
    def builder = new ProcessBuilder(command).directory(directory)
    builder.environment().putAll(gitEnvironment)
    extra.each { key, value -> builder.environment()[key.toString()] = value.toString() }
    def process = builder.start()
    def output = new StringBuffer()
    def errors = new StringBuffer()
    process.waitForProcessOutput(output, errors)
    [status: process.exitValue(), output: output.toString(), errors: errors.toString()]
}
def git = { File directory, List<String> arguments ->
    def result = run(directory, ['git'] + arguments)
    assert result.status == 0 : "git ${arguments}: ${result.errors}"
    result.output.trim()
}
// Emit Signing Request runs the real schema validator as well as the Git stages.
// Use a Python environment with scripts/requirements-signing.txt installed.
def signingPython = System.getenv('RELEASE_SIGNING_TEST_PYTHON') ?:
    run(root, ['python3', '-c', 'import sys; print(sys.executable)']).output.trim()
def put = { File directory, String path, String content ->
    def file = new File(directory, path)
    file.parentFile.mkdirs()
    file.text = content
}
def createFixture = { String name ->
    def directory = new File(root, name)
    directory.mkdirs()
    def origin = new File(directory, 'origin.git')
    git(directory, ['init', '--bare', origin.path])
    def seed = new File(directory, 'seed')
    git(directory, ['init', '-b', 'master', seed.path])
    git(seed, ['config', 'user.name', 'Release Test'])
    git(seed, ['config', 'user.email', 'release@example.invalid'])
    put(seed, 'sys/conf/newvers.sh', 'BRANCH="DEVELOPMENT_6_5"\n\nTYPE="DragonFly"\n')
    put(seed, 'etc/Makefile.usr', 'BRANCH?=master\nBRANCH_PROJECTS?=master\n')
    put(seed, 'sys/sys/param.h', '''/*
 * 600400 - 6.4 release
 * 600500 - 6.5 development
 */
#undef __DragonFly_version
#define __DragonFly_version 600522\t/* propagated to newvers */
''')
    git(seed, ['add', '.'])
    git(seed, ['commit', '-m', 'Development source'])
    git(seed, ['remote', 'add', 'origin', origin.toURI().toString().replaceFirst('^file:/', 'file:///')])
    git(seed, ['push', 'origin', 'master'])
    git(seed, ['checkout', '-b', 'DragonFly_RELEASE_6_4'])
    put(seed, 'sys/conf/newvers.sh', 'BRANCH="RELEASE_6_4"\n\nTYPE="DragonFly"\n')
    put(seed, 'etc/Makefile.usr', 'BRANCH?=DragonFly_RELEASE_6_4\nBRANCH_PROJECTS?=master\n')
    git(seed, ['add', '.'])
    git(seed, ['commit', '-m', 'Release source'])
    ['v6.4.0', 'v6.4.1', 'v6.4.2'].each { git(seed, ['tag', it]) }
    git(seed, ['push', 'origin', 'DragonFly_RELEASE_6_4', '--tags'])
    git(seed, ['checkout', 'master'])
    [directory:directory, origin:origin, seed:seed]
}
def counter = 0
def newBuild = { Map fixture, Map overrides = [:] ->
    def workspace = new File(fixture.directory, "build-${++counter}")
    workspace.mkdirs()
    def params = [ORIGIN_URL:fixture.origin.toURI().toString().replaceFirst('^file:/', 'file:///'), BASE_BRANCH:'master',
                  VERSION_BUMP:'minor', TARGET_VERSION:'', RELEASE_STAGE:'rc', RC_NUMBER:'1', DRY_RUN:false] + overrides
    def env = [WORKSPACE:workspace.path, GIT_AUTHOR_NAME:'Release Test', GIT_AUTHOR_EMAIL:'release@example.invalid',
               ALLOWED_ORIGIN_PREFIX:fixture.origin.toURI().toString().replaceFirst('^file:/', 'file:///'), GIT_CREDENTIALS_ID:'unused',
               RELEASE_APPROVERS:'tester', BUILD_TAG:'local-test', SIGNING_PYTHON:signingPython]
    params.each { key, value -> env[key] = value.toString() }
    def binding = new Binding([params:params, env:env])
    def script = new GroovyShell(binding).parse(pipelineFile)
    def stages = [:]
    ['pipeline', 'stages', 'script'].each { name ->
        script.metaClass."${name}" = { Closure body -> body.call() }
    }
    ['agent', 'options', 'parameters', 'environment', 'post'].each { name ->
        script.metaClass."${name}" = { Closure ignored -> }
    }
    boolean enabled = true
    script.metaClass.when = { Closure body -> body.call() }
    script.metaClass.expression = { Closure body -> enabled = enabled && body.call() }
    script.metaClass.steps = { Closure body -> if (enabled) { body.call() } }
    script.metaClass.stage = { String name, Closure body ->
        stages[name] = { enabled = true; body.call() }
    }
    script.metaClass.usernamePassword = { Map arguments -> arguments }
    script.metaClass.withCredentials = { List ignored, Closure body -> body.call() }
    script.metaClass.deleteDir = { assert workspace.list().length == 0 }
    script.metaClass.readFile = { String path -> new File(workspace, path).text }
    script.metaClass.writeFile = { Map arguments -> put(workspace, arguments.file, arguments.text.toString()) }
    script.metaClass.archiveArtifacts = { Map ignored -> }
    script.metaClass.error = { String message -> throw new IllegalArgumentException(message) }
    script.metaClass.sh = { Object arguments ->
        String command = arguments instanceof Map ? arguments.script : arguments.toString()
        def result = run(workspace, ['/bin/sh', '-c', command], env)
        if (arguments instanceof Map && arguments.returnStatus) {
            return result.status
        }
        if (result.status != 0) {
            throw new IllegalStateException("Shell failed (${result.status}): ${result.output}\n${result.errors}")
        }
        return arguments instanceof Map && arguments.returnStdout ? result.output : null
    }
    script.run()
    [workspace:workspace, env:env, params:params, stage:{ String name ->
        stages[name].call()
        if (name == 'Clone Origin') {
            // Checkout Release Tools normally supplies these files on Jenkins.
            ['scripts/release_signing.py', 'scripts/validate-release-signing-request',
             'schemas/release-signing-request.schema.json'].each { path ->
                put(workspace, "tools/${path}", new File(pipelineFile.parentFile.parentFile, path).text)
            }
        }
    }]
}
def prepare = { Map build ->
    ['Validate Parameters', 'Clone Origin', 'Compute Release Plan', 'Check Remote Refs',
     'Prepare Release Changes'].each { build.stage(it) }
}
def rejects = { String expected, Closure operation ->
    boolean rejected = false
    try {
        operation()
    } catch (IllegalArgumentException | IllegalStateException failure) {
        assert failure.message.contains(expected) : failure.message
        rejected = true
    }
    assert rejected : "Expected failure containing: ${expected}"
}
def head = { Map fixture, String ref -> git(fixture.origin, ['rev-parse', ref]) }
def missing = { Map fixture, String ref -> run(fixture.origin, ['git', 'show-ref', '--verify', ref]).status != 0 }

try {
    def basic = createFixture('basic')
    def originalMaster = head(basic, 'master')
    def creation = newBuild(basic)
    prepare(creation)
    creation.stage('Commit And Push Branches')
    creation.stage('Emit Signing Request')
    def released = head(basic, 'DragonFly_RELEASE_6_6')
    def development = head(basic, 'master')
    assert development != originalMaster
    assert git(basic.origin, ['show', 'master:sys/conf/newvers.sh']).contains('DEVELOPMENT_6_7')
    assert git(basic.origin, ['show', 'DragonFly_RELEASE_6_6:sys/conf/newvers.sh']).contains('RELEASE_6_6')
    def history = git(basic.origin, ['show', 'master:sys/sys/param.h'])
    assert history.indexOf('600600 - 6.6 release') < history.indexOf('600700 - 6.7 development')
    assert history.contains('600600 - 6.6 release')
    assert new File(creation.workspace, 'release-signing-request.json').text.contains(released)

    // Subsequent RC, final, and retry after a published tag: no commits/pushes.
    git(basic.origin, ['update-ref', 'refs/tags/v6.6.0rc1', released])
    [[RELEASE_STAGE:'rc', RC_NUMBER:'2'], [RELEASE_STAGE:'final']].each { options ->
        def tagBuild = newBuild(basic, [BASE_BRANCH:'DragonFly_RELEASE_6_6', VERSION_BUMP:'none'] + options)
        prepare(tagBuild)
        assert new File(tagBuild.workspace, 'master.patch').length() == 0
        assert new File(tagBuild.workspace, 'release-branch.patch').length() == 0
        assert tagBuild.env.NEXT_DEV_VERSION == 'unchanged'
        tagBuild.stage('Commit And Push Branches')
        tagBuild.stage('Emit Signing Request')
        assert tagBuild.env.RELEASE_COMMIT == released
        assert head(basic, 'master') == development
        assert head(basic, 'DragonFly_RELEASE_6_6') == released
    }
    git(basic.origin, ['update-ref', 'refs/tags/v6.6.0', released])
    def retry = newBuild(basic, [BASE_BRANCH:'DragonFly_RELEASE_6_6', VERSION_BUMP:'none', RELEASE_STAGE:'final'])
    prepare(retry)
    retry.stage('Commit And Push Branches')
    assert retry.env.RELEASE_COMMIT == released
    // Existing tags are only accepted here for later GPG verification; no tag is replaced.
    assert head(basic, 'refs/tags/v6.6.0') == released

    def patch = newBuild(basic, [BASE_BRANCH:'DragonFly_RELEASE_6_4', VERSION_BUMP:'patch', RELEASE_STAGE:'final'])
    prepare(patch)
    assert patch.env.TARGET_VERSION_COMPUTED == '6.4.3'
    def previousRelease = head(basic, 'DragonFly_RELEASE_6_4')
    patch.stage('Commit And Push Branches')
    assert patch.env.RELEASE_COMMIT == previousRelease
    assert head(basic, 'master') == development
    assert head(basic, 'DragonFly_RELEASE_6_4') == previousRelease

    def wrongBase = newBuild(basic, [BASE_BRANCH:'DragonFly_RELEASE_6_4'])
    rejects('must start from master') { prepare(wrongBase) }
    def wrongPatchBase = newBuild(basic, [VERSION_BUMP:'patch', TARGET_VERSION:'6.4.3'])
    rejects('requires BASE_BRANCH=DragonFly_RELEASE_6_4') { prepare(wrongPatchBase) }

    def majorFixture = createFixture('major')
    def major = newBuild(majorFixture, [VERSION_BUMP:'major'])
    prepare(major)
    major.stage('Commit And Push Branches')
    assert git(majorFixture.origin, ['show', 'master:sys/conf/newvers.sh']).contains('DEVELOPMENT_7_1')
    assert git(majorFixture.origin, ['show', 'DragonFly_RELEASE_7_0:sys/conf/newvers.sh']).contains('RELEASE_7_0')

    def dryFixture = createFixture('dry-run')
    def dry = newBuild(dryFixture, [DRY_RUN:true])
    def dryMaster = head(dryFixture, 'master')
    prepare(dry)
    dry.stage('Commit And Push Branches')
    dry.stage('Emit Signing Request')
    assert head(dryFixture, 'master') == dryMaster
    assert missing(dryFixture, 'refs/heads/DragonFly_RELEASE_6_6')
    assert !new File(dry.workspace, 'release-signing-request.json').exists()

    // Concurrent creation of the release branch must not be overwritten.
    def branchRaceFixture = createFixture('branch-race')
    def branchRace = newBuild(branchRaceFixture)
    prepare(branchRace)
    def branchRaceMaster = head(branchRaceFixture, 'master')
    git(branchRaceFixture.origin, ['update-ref', 'refs/heads/DragonFly_RELEASE_6_6', branchRaceMaster])
    rejects('Shell failed') { branchRace.stage('Commit And Push Branches') }
    assert head(branchRaceFixture, 'master') == branchRaceMaster
    assert head(branchRaceFixture, 'DragonFly_RELEASE_6_6') == branchRaceMaster

    // A receiver that rejects one branch must receive neither update.
    def rejectedFixture = createFixture('rejected')
    def rejected = newBuild(rejectedFixture)
    prepare(rejected)
    def oldMaster = head(rejectedFixture, 'master')
    put(rejectedFixture.origin, 'hooks/update', '#!/bin/sh\n[ "$1" != refs/heads/master ]\n')
    assert new File(rejectedFixture.origin, 'hooks/update').setExecutable(true)
    rejects('Shell failed') { rejected.stage('Commit And Push Branches') }
    assert head(rejectedFixture, 'master') == oldMaster
    assert missing(rejectedFixture, 'refs/heads/DragonFly_RELEASE_6_6')

    // Reject even a concurrent fast-forward made during the approval interval.
    def raceFixture = createFixture('race')
    def raced = newBuild(raceFixture)
    prepare(raced)
    put(raceFixture.seed, 'concurrent', 'Another developer\n')
    git(raceFixture.seed, ['add', '.'])
    git(raceFixture.seed, ['commit', '-m', 'Concurrent development'])
    git(raceFixture.seed, ['push', 'origin', 'master'])
    def concurrentMaster = head(raceFixture, 'master')
    rejects('Shell failed') { raced.stage('Commit And Push Branches') }
    assert head(raceFixture, 'master') == concurrentMaster
    assert missing(raceFixture, 'refs/heads/DragonFly_RELEASE_6_6')

    def unsupportedFixture = createFixture('no-atomic')
    def unsupported = newBuild(unsupportedFixture)
    prepare(unsupported)
    def unsupportedMaster = head(unsupportedFixture, 'master')
    git(unsupportedFixture.origin, ['config', 'receive.advertiseAtomic', 'false'])
    rejects('does not support --atomic push') { unsupported.stage('Commit And Push Branches') }
    assert head(unsupportedFixture, 'master') == unsupportedMaster
    assert missing(unsupportedFixture, 'refs/heads/DragonFly_RELEASE_6_6')

    // Existing release tips are also pinned across approval.
    def changedRelease = newBuild(basic, [BASE_BRANCH:'DragonFly_RELEASE_6_4', VERSION_BUMP:'patch'])
    prepare(changedRelease)
    git(basic.origin, ['update-ref', 'refs/heads/DragonFly_RELEASE_6_4', released])
    rejects('changed during approval') { changedRelease.stage('Commit And Push Branches') }

    println 'Release workflow integration checks passed'
} finally {
    assert root.deleteDir()
}
