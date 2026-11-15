// Run from the repository root: groovy tests/release-version.groovy
// Load the actual version helpers without executing the Jenkins pipeline.
def source = new File('jenkins/Jenkinsfile.release-git').text
def helpers = new GroovyShell().parse(source.substring(0, source.indexOf('\npipeline {')))
helpers.metaClass.error = { String message -> throw new IllegalArgumentException(message) }

def release = [kind: 'RELEASE', major: 6, minor: 4]
def development = [kind: 'DEVELOPMENT', major: 6, minor: 5]
def refs = { List<String> names ->
    names.collect { "${'a' * 40}\trefs/tags/${it}" }.join('\n')
}
def tags = refs([
    'v6.4.0', 'v6.4.9', 'v6.4.2', 'v6.4.10',
    'v6.4.11rc1', 'v6.6.99', 'v5.4.99', 'unrelated'
])

assert helpers.computeTargetVersion(release, 'patch', '', tags) ==
    [major: 6, minor: 4, patch: 11]
assert helpers.computeTargetVersion(release, 'patch', '', '') ==
    [major: 6, minor: 4, patch: 1]
assert helpers.computeTargetVersion(release, 'patch', '', refs(['v6.4.0'])) ==
    [major: 6, minor: 4, patch: 1]
assert helpers.computeTargetVersion(release, 'patch', '', refs(['v6.4.2rc1'])) ==
    [major: 6, minor: 4, patch: 1]
assert helpers.computeTargetVersion(release, 'patch', '6.4.12', tags) ==
    [major: 6, minor: 4, patch: 12]
assert helpers.computeTargetVersion(development, 'patch', '6.4.12', tags) ==
    [major: 6, minor: 4, patch: 12]

boolean rejectedDevelopment = false
try {
    helpers.computeTargetVersion(development, 'patch', '', tags)
} catch (IllegalArgumentException expected) {
    rejectedDevelopment = true
}
assert rejectedDevelopment

assert helpers.computeTargetVersion(release, 'minor', '', tags) ==
    [major: 6, minor: 6, patch: 0]
assert helpers.computeTargetVersion(development, 'minor', '', tags) ==
    [major: 6, minor: 6, patch: 0]
assert helpers.computeTargetVersion(release, 'major', '', tags) ==
    [major: 7, minor: 0, patch: 0]
assert helpers.computeTargetVersion(release, 'none', '', tags) ==
    [major: 6, minor: 4, patch: 0]

def rejects = { Closure operation ->
    boolean rejected = false
    try {
        operation()
    } catch (IllegalArgumentException expected) {
        rejected = true
    }
    assert rejected
}
assert helpers.releaseAction(development, 'master', 'minor', [major:6, minor:6, patch:0]) == 'create'
assert helpers.releaseAction(development, 'master', 'major', [major:7, minor:0, patch:0]) == 'create'
rejects { helpers.releaseAction(release, 'DragonFly_RELEASE_6_4', 'minor', [major:6, minor:6, patch:0]) }
rejects { helpers.releaseAction(release, 'master', 'major', [major:7, minor:0, patch:0]) }
rejects { helpers.releaseAction(development, 'feature', 'minor', [major:6, minor:6, patch:0]) }
rejects { helpers.releaseAction(development, 'master', 'minor', [major:6, minor:4, patch:0]) }
assert helpers.releaseAction(release, 'DragonFly_RELEASE_6_4', 'patch', [major:6, minor:4, patch:3]) == 'tag'
// Subsequent RCs and final releases use the same existing branch, including .0.
assert helpers.releaseAction(release, 'DragonFly_RELEASE_6_4', 'none', [major:6, minor:4, patch:0]) == 'tag'
assert helpers.releaseAction(release, 'DragonFly_RELEASE_6_4', 'none', [major:6, minor:4, patch:3]) == 'tag'
assert helpers.computeTargetVersion(release, 'none', '6.4.3', tags) == [major:6, minor:4, patch:3]
rejects { helpers.releaseAction(development, 'master', 'none', [major:6, minor:6, patch:0]) }
rejects { helpers.releaseAction(development, 'master', 'patch', [major:6, minor:4, patch:3]) }
rejects { helpers.releaseAction(release, 'DragonFly_RELEASE_6_2', 'patch', [major:6, minor:4, patch:3]) }
rejects { helpers.releaseAction(release, 'DragonFly_RELEASE_6_4', 'patch', [major:6, minor:6, patch:1]) }

// Match the structure of the historical 6.4/6.5 development bump (81dd9c6184).
def files = [
    'src/sys/conf/newvers.sh': 'BRANCH="DEVELOPMENT_6_3"\n\nTYPE="DragonFly"\n',
    'src/sys/sys/param.h': '''/*
 * 600302 - change sysctl KERN_PROC behavior
 */
#undef __DragonFly_version
#define __DragonFly_version 600302\t/* propagated to newvers */
'''
]
helpers.metaClass.readFile = { String path -> files[path] }
helpers.metaClass.writeFile = { Map arguments -> files[arguments.file] = arguments.text }
helpers.updateNewvers('src', 'DEVELOPMENT', [major:6, minor:5, patch:0])
helpers.updateParamH('src', [major:6, minor:5, patch:0], 'development', [major:6, minor:4, patch:0])
assert files['src/sys/conf/newvers.sh'] == 'BRANCH="DEVELOPMENT_6_5"\n\nTYPE="DragonFly"\n'
assert files['src/sys/sys/param.h'] == '''/*
 * 600302 - change sysctl KERN_PROC behavior
 * 600400 - 6.4 release
 * 600500 - 6.5 development
 */
#undef __DragonFly_version
#define __DragonFly_version 600500\t/* propagated to newvers */
'''

println 'Release version and branch checks passed'
