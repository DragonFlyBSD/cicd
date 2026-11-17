// Run with Groovy 2.4 and groovy-cps + Guava on the classpath. See docs/release-pipeline.md.
// Unlike the plain helper checks, these run the actual helper through the CPS transform.
import com.cloudbees.groovy.cps.Continuable
import com.cloudbees.groovy.cps.CpsTransformer
import org.codehaus.groovy.control.CompilerConfiguration

def source = new File(args.length ? args[0] : 'jenkins/Jenkinsfile.release-git').text
def helpers = source.substring(0, source.indexOf('\npipeline {'))
def configuration = new CompilerConfiguration()
configuration.addCompilationCustomizers(new CpsTransformer())
def evaluate = { String checks ->
    def script = new GroovyShell(configuration).parse(helpers + '\n' + checks)
    new Continuable(script).run(null)
}
assert evaluate('''
return nextPatchVersion([major:6, minor:4], """${'a' * 40}\trefs/tags/v6.4.0
${'a' * 40}\trefs/tags/v6.4.1
${'a' * 40}\trefs/tags/v6.4.2
""")
''') == 3
assert evaluate('''
return nextPatchVersion([major:6, minor:4], """${'a' * 40}\trefs/tags/v6.4.9
${'a' * 40}\trefs/tags/v6.6.99
${'a' * 40}\trefs/tags/v6.4.10
${'a' * 40}\trefs/tags/v6.4.11rc1
""")
''') == 11
assert evaluate("return nextPatchVersion([major:6, minor:4], '')") == 1
println 'CPS release version checks passed'
