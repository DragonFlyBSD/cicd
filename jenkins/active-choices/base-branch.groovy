// Active Choices "Groovy Script" for the BASE_BRANCH job parameter.
//
// Reference copy. The live copy is pasted into the Active Choices Parameter in
// the Jenkins job UI, because Active Choices parameters cannot be declared from
// the Jenkinsfile. Keep this file in sync with the UI by hand.
//
// Lists release-relevant branches from the canonical DragonFly repo so the
// dropdown stays current. This only POPULATES the dropdown; the pipeline still
// clones/commits/tags against the writable ORIGIN_URL, never this repo.
//
// Setup notes:
//   - Uncheck "Use Groovy Sandbox" (it runs git ls-remote via .execute()).
//   - First use needs approval in Manage Jenkins -> In-process Script Approval.
//   - Pair with base-branch-fallback.groovy as the Active Choices fallback.
//
// There is intentionally no hardcoded branch list and no silent fallback to
// master: on any failure this returns an "ERROR:" sentinel, which the pipeline's
// Validate Parameters stage rejects.

def proc = ['git', 'ls-remote', '--heads',
            'git://git.dragonflybsd.org/dragonfly.git'].execute()
proc.waitForOrKill(30000)
if (proc.exitValue() != 0) {
    return ['ERROR: git ls-remote failed']
}

boolean hasMaster = false
def releases = []
proc.text.eachLine { line ->
    def m = line =~ /refs\/heads\/(.+)$/
    if (!m) {
        return
    }
    def branch = m[0][1].trim()
    if (branch == 'master') {
        hasMaster = true
    } else if (branch ==~ /DragonFly_RELEASE_\d+_\d+/) {
        releases << branch
    }
}

// Newest release branch first, compared numerically (so 6_10 sorts above 6_8).
releases = releases.unique().sort { a, b ->
    def va = (a =~ /(\d+)_(\d+)/)[0]
    def vb = (b =~ /(\d+)_(\d+)/)[0]
    [vb[1].toInteger(), vb[2].toInteger()] <=> [va[1].toInteger(), va[2].toInteger()]
}

def result = []
if (hasMaster) {
    result << 'master'
}
result.addAll(releases)

if (result.isEmpty()) {
    return ['ERROR: no branches returned']
}
return result
