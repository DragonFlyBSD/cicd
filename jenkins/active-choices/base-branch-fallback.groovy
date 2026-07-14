// Active Choices "Fallback Script" for the BASE_BRANCH job parameter.
//
// Reference copy; the live copy is pasted into the Active Choices Parameter's
// fallback field in the Jenkins job UI.
//
// Runs only if base-branch.groovy throws. It must NOT return 'master' or any
// hardcoded branch list: the pipeline's Validate Parameters stage rejects any
// BASE_BRANCH value starting with 'ERROR', so a broken branch listing fails the
// build instead of silently defaulting to master.
return ['ERROR: branch listing unavailable']
