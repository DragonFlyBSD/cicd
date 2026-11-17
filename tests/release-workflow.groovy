// Run from the repository root: groovy tests/release-workflow.groovy
// Execute the actual pipeline's Git stages against disposable local repositories.
// This harness supplies Pipeline steps and uses real Git/GPG; it does not emulate Jenkins CPS.
def pipelineFile = new File('jenkins/Jenkinsfile.release-git').canonicalFile
def root = File.createTempDir('release-workflow-', '')
def gitEnvironment = [GIT_CONFIG_NOSYSTEM:'1', GIT_CONFIG_GLOBAL:'/dev/null']
def run = { File directory, List<String> command, Map extra = [:], String input = null ->
    def builder = new ProcessBuilder(command).directory(directory)
    builder.environment().putAll(gitEnvironment)
    extra.each { key, value -> builder.environment()[key.toString()] = value.toString() }
    def process = builder.start()
    if (input != null) {
        process.outputStream.withWriter('UTF-8') { writer -> writer.write(input) }
    }
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
def newBuild = { Map fixture, Map overrides = [:], Map environmentOverrides = [:] ->
    def workspace = new File(fixture.directory, "build-${++counter}")
    workspace.mkdirs()
    def params = [ORIGIN_URL:fixture.origin.toURI().toString().replaceFirst('^file:/', 'file:///'), BASE_BRANCH:'master',
                  VERSION_BUMP:'minor', TARGET_VERSION:'', RELEASE_STAGE:'rc', RC_NUMBER:'1', DRY_RUN:false] + overrides
    def env = [WORKSPACE:workspace.path, GIT_AUTHOR_NAME:'Release Test', GIT_AUTHOR_EMAIL:'release@example.invalid',
               ALLOWED_ORIGIN_PREFIX:fixture.origin.toURI().toString().replaceFirst('^file:/', 'file:///'), GIT_CREDENTIALS_ID:'unused',
               RELEASE_TOOLS_COMMIT:('a' * 40), RELEASE_APPROVERS:'tester', BUILD_TAG:'local-test', SIGNING_PYTHON:signingPython]
    env.putAll(environmentOverrides)
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
    def archives = []
    script.metaClass.archiveArtifacts = { Map arguments -> archives << arguments.artifacts }
    script.metaClass.echo = { String ignored -> }
    script.metaClass.timeout = { Map ignored, Closure body -> body.call() }
    script.metaClass.waitUntil = { Map ignored, Closure body ->
        if (!body.call()) { throw new IllegalStateException('Local harness: still waiting') }
    }
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
    [workspace:workspace, env:env, params:params, archives:archives, stage:{ String name ->
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
    patch.stage('Archive Review Material')
    def metadata = new groovy.json.JsonSlurper().parseText(new File(patch.workspace, 'release-metadata.json').text)
    assert metadata.current_branch_marker == 'RELEASE_6_4'
    def previousRelease = head(basic, 'DragonFly_RELEASE_6_4')
    patch.stage('Commit And Push Branches')
    patch.stage('Emit Signing Request')
    def approvalCommand = new File(patch.workspace, 'tag-command.txt').readLines()
        .find { it.startsWith('#   python scripts/release-sign approve ') }
    assert approvalCommand.contains('--source-dir /path/to/source --request release-signing-request.json')
    assert !approvalCommand.substring(1).contains('#')
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

    // Full two-person release: pinned executable tools and keys, moving approval DATA.
    def gpgHome = new File(run(root, [signingPython, '-c',
        "import tempfile; print(tempfile.mkdtemp(prefix='dfwf-', dir='/tmp'))"]).output.trim())
    try {
        def keyEnv = [GNUPGHOME:gpgHome.path]
        def gpg = { List<String> args ->
            def result = run(root, ['gpg', '--no-options', '--batch', '--pinentry-mode',
                'loopback', '--passphrase', ''] + args, keyEnv)
            assert result.status == 0 : result.errors
            result.output
        }
        gpg(['--quick-generate-key', 'First Test <first@example.invalid>', 'ed25519', 'sign', '0'])
        gpg(['--quick-generate-key', 'Second Test <second@example.invalid>', 'ed25519', 'sign', '0'])
        def fingerprints = gpg(['--with-colons', '--list-keys']).readLines()
            .findAll { it.startsWith('fpr:') }.collect { it.split(':')[9] }
        assert fingerprints.size() == 2
        def toolsSeed = new File(root, 'trusted-tools')
        toolsSeed.mkdirs()
        git(toolsSeed, ['init', '-b', 'master'])
        git(toolsSeed, ['config', 'user.name', 'Trusted Tools'])
        git(toolsSeed, ['config', 'user.email', 'tools@example.invalid'])
        ['scripts', 'schemas'].each { dir ->
            new File(pipelineFile.parentFile.parentFile, dir).eachFile { file ->
                if (file.isFile()) { put(toolsSeed, "${dir}/${file.name}", file.text) }
            }
        }
        fingerprints.eachWithIndex { fingerprint, index ->
            put(toolsSeed, "keys/authorized-signers/key-${index}.asc", gpg(['--armor', '--export', fingerprint]))
        }
        put(toolsSeed, '.gitignore', '__pycache__/\n*.py[cod]\n')
        git(toolsSeed, ['add', '.'])
        git(toolsSeed, ['commit', '-m', 'Reviewed tools and two authorized keys'])
        def pin = git(toolsSeed, ['rev-parse', 'HEAD'])
        def publicationOrigin = new File(root, 'approval-publication.git')
        git(root, ['init', '--bare', publicationOrigin.path])
        git(toolsSeed, ['remote', 'add', 'origin', publicationOrigin.path])
        git(toolsSeed, ['push', 'origin', 'master'])
        def signedFixture = createFixture('signed-release')
        def signedBuild = newBuild(signedFixture, [RELEASE_TOOLS_COMMIT:pin], [
            RELEASE_TOOLS_URL:toolsSeed.toURI().toString().replaceFirst('^file:/', 'file:///'),
            RELEASE_APPROVALS_REF:'master',
            SIGNED_TAG_TIMEOUT_HOURS:'1', SECOND_APPROVAL_TIMEOUT_HOURS:'1',
            PATH:new File(signingPython).parent + ':' + System.getenv('PATH')])
        ['Validate Parameters', 'Clone Origin', 'Checkout Release Tools', 'Compute Release Plan',
         'Check Remote Refs', 'Prepare Release Changes', 'Commit And Push Branches',
         'Emit Signing Request'].each { signedBuild.stage(it) }
        def trusted = new File(signedBuild.workspace, 'tools')
        assert git(trusted, ['rev-parse', 'HEAD']) == pin
        def request = new File(signedBuild.workspace, 'release-signing-request.json')
        def releaseTree = new File(signedBuild.workspace, 'release-src')
        def sourceOrigin = signedFixture.origin.toURI().toString().replaceFirst('^file:/', 'file:///')
        def missingTag = run(signedBuild.workspace, [signingPython,
            new File(trusted, 'scripts/poll-release-signing').path, 'tag'], signedBuild.env)
        assert missingTag.status == 2 : missingTag.errors
        def cancelled = run(releaseTree, [signingPython, new File(trusted, 'scripts/release-sign').path,
            'tag', '--source-dir', releaseTree.path, '--request', request.path,
            '--key', fingerprints[0], '--origin', sourceOrigin], keyEnv, 'cancel\n')
        assert cancelled.status != 0
        assert run(releaseTree, ['git', 'show-ref', '--verify', 'refs/tags/v6.6.0rc1']).status != 0
        def signedResult = run(releaseTree, [signingPython, new File(trusted, 'scripts/release-sign').path, 'tag',
            '--source-dir', releaseTree.path, '--request', request.path, '--key', fingerprints[0], '--origin', sourceOrigin], keyEnv, 'publish\nv6.6.0rc1\n')
        assert signedResult.status == 0 : signedResult.errors
        signedBuild.stage('Await Signed Tag')
        rejects('still waiting') { signedBuild.stage('Await Second Approval') }
        def approvalResult = run(releaseTree, [signingPython, new File(toolsSeed, 'scripts/release-sign').path, 'approve',
            '--source-dir', releaseTree.path, '--request', request.path, '--key', fingerprints[1], '--origin', sourceOrigin], keyEnv, 'publish\nv6.6.0rc1\n')
        assert approvalResult.status == 0 : approvalResult.errors
        assert git(toolsSeed, ['diff-tree', '--no-commit-id', '--name-only', '-r', 'HEAD']).readLines().sort() ==
            ['approvals/v6.6.0rc1/release.json', 'approvals/v6.6.0rc1/second-signature.asc']
        assert git(toolsSeed, ['rev-parse', 'HEAD']) == git(publicationOrigin, ['rev-parse', 'master'])
        // A newer approval commit also changes scripts and keys. Those changes
        // must never influence the pinned verification executed by Jenkins.
        def marker = new File(root, 'untrusted-verifier-executed')
        put(toolsSeed, 'scripts/verify-release-approval', "#!/bin/sh\ntouch '${marker.path}'\nexit 0\n")
        assert new File(toolsSeed, 'keys/authorized-signers/key-1.asc').delete()
        git(toolsSeed, ['add', '.'])
        git(toolsSeed, ['commit', '-m', 'Publish approval alongside untrusted tool changes'])
        signedBuild.stage('Await Second Approval')
        assert !marker.exists()
        def report = new File(signedBuild.workspace, 'release-approval/verification.json').text
        assert report.contains(fingerprints[0]) && report.contains(fingerprints[1])
        assert new File(signedBuild.workspace, 'release-approval/source-commit.txt').text.trim() ==
            git(toolsSeed, ['rev-parse', 'HEAD'])
        assert signedBuild.archives.contains('release-approval/*')
        assert git(trusted, ['rev-parse', 'HEAD']) == pin
        // Even an authentic second signature by the FIRST signer is insufficient.
        def approvalDir = new File(toolsSeed, 'approvals/v6.6.0rc1')
        gpg(['--yes', '--armor', '--detach-sign', '--local-user', fingerprints[0],
             '--output', new File(approvalDir, 'second-signature.asc').path,
             new File(approvalDir, 'release.json').path])
        git(toolsSeed, ['add', '.'])
        git(toolsSeed, ['commit', '-m', 'Publish insufficient same-person approval'])
        rejects('Approval fetch or verification failed') { signedBuild.stage('Await Second Approval') }
        // A tag replacement during the second-approval wait must not be hidden
        // by src's old fetched ref, even if the replacement is signed by key A.
        gpg(['--yes', '--armor', '--detach-sign', '--local-user', fingerprints[1],
             '--output', new File(approvalDir, 'second-signature.asc').path,
             new File(approvalDir, 'release.json').path])
        git(toolsSeed, ['add', '.'])
        git(toolsSeed, ['commit', '-m', 'Restore independent approval'])
        def replacement = run(releaseTree, ['git', '-c', 'gpg.format=openpgp', 'tag', '-s', '-f',
            '-u', fingerprints[0], '-m', 'DragonFly 6.6.0rc1', 'v6.6.0rc1'],
            keyEnv + [GIT_COMMITTER_DATE:'2001-01-01T00:00:00Z'])
        assert replacement.status == 0 : replacement.errors
        git(releaseTree, ['push', '--force', 'origin', 'refs/tags/v6.6.0rc1'])
        rejects('Approval fetch or verification failed') { signedBuild.stage('Await Second Approval') }
        def badPin = newBuild(createFixture('bad-pin'), [:], [RELEASE_TOOLS_COMMIT:'master'])
        rejects('reviewed full commit ID') { badPin.stage('Validate Parameters') }
        def editablePin = newBuild(createFixture('editable-pin'), [RELEASE_TOOLS_COMMIT:pin])
        editablePin.stage('Validate Parameters')
        assert editablePin.env.RELEASE_TOOLS_COMMIT == pin
        def badParameterPin = newBuild(createFixture('bad-parameter-pin'), [RELEASE_TOOLS_COMMIT:'master'])
        rejects('reviewed full commit ID') { badParameterPin.stage('Validate Parameters') }
    } finally {
        run(root, ['gpgconf', '--homedir', gpgHome.path, '--kill', 'all'])
        assert gpgHome.deleteDir()
    }
    println 'Release workflow and two-person signing integration checks passed'
} finally {
    assert root.deleteDir()
}
