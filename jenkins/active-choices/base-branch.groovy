// Active Choices "Groovy Script" for the BASE_BRANCH job parameter.
//
// Reference copy. The live copy is pasted into the Active Choices Parameter in
// the Jenkins job UI, because this pipeline deliberately leaves parameter setup in
// the job UI. Keep this file in sync with the UI by hand.
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

try {
    def output = new StringBuffer()
    def errors = new StringBuffer()
    def process = [
        'git', 'ls-remote', '--heads',
        'git://git.dragonflybsd.org/dragonfly.git'
    ].execute()

    process.consumeProcessOutput(output, errors)
    process.waitForOrKill(15000)

    if (process.exitValue() != 0) {
        return ['ERROR: git failed or timed out']
    }

    def branches = []
    output.toString().eachLine { line ->
        def fields = line.tokenize()
        if (fields.size() == 2) {
            def branch = fields[1].replaceFirst('^refs/heads/', '')
            if (branch == 'master' ||
                branch.matches('DragonFly_RELEASE_[0-9]+_[0-9]+')) {
                branches.add(branch)
            }
        }
    }

    return branches ? branches : ['ERROR: no matching branches']
} catch (Exception e) {
    return ['ERROR: ' + e.toString()]
}
