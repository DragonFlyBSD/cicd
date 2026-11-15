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

println 'Release version checks passed'
