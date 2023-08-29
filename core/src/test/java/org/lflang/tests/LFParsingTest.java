/*
 * generated by Xtext 2.23.0
 */
package org.lflang.tests;

import com.google.inject.Inject;
import java.util.List;
import org.eclipse.emf.ecore.resource.Resource.Diagnostic;
import org.eclipse.xtext.testing.InjectWith;
import org.eclipse.xtext.testing.extensions.InjectionExtension;
import org.eclipse.xtext.testing.util.ParseHelper;
import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lflang.lf.Model;

@ExtendWith(InjectionExtension.class)
@InjectWith(LFInjectorProvider.class)
public class LFParsingTest {

  @Inject private ParseHelper<Model> parseHelper;

  @Test
  public void testLexingEmptyTargetProperties() throws Exception {
    assertNoParsingErrorsIn("target C { };      \nreactor Foo {}");
    assertNoParsingErrorsIn("target C {a:b,};   \nreactor Foo {}");
    expectParsingErrorIn("target C {,};      \nreactor Foo {}");

    // array elements
    // assertNoParsingErrorsIn("target C {x:[ ]};  \nreactor Foo {}");
    // assertNoParsingErrorsIn("target C {x:[]};   \nreactor Foo {}");
    // assertNoParsingErrorsIn("target C {x:[,]};  \nreactor Foo {}");
  }

  @Test
  public void testLexingLifetimeAnnots() throws Exception {
    assertNoParsingErrorsIn(
        makeLfTargetCode(
            "Rust",
            "        struct Hello<'a> { \n"
                + "            r: &'a str,\n"
                + "            r2: &'a Box<Hello<'a>>,\n"
                + "        }"));
  }

  @Test
  public void testLexingSingleLifetimeAnnot() throws Exception {
    // just to be sure, have a single lifetime annot.
    assertNoParsingErrorsIn(
        makeLfTargetCode(
            "Rust", "        struct Hello { \n" + "            r: &'static str,\n" + "        }"));
  }

  @Test
  public void testLexingNewlineCont() throws Exception {
    /*
    This example looks like this:
    "a\
    bcde"

    This is valid C++ to escape a newline.
     */

    assertNoParsingErrorsIn(makeLfTargetCode("Cpp", "        \"a\\\n" + "        bcde\"\n"));
  }

  @Test
  public void testLexingSquotedString() throws Exception {
    // we can't do that anymore
    expectParsingErrorIn(makeLfTargetCode("Python", "a = ' a string '"));
  }

  @Test
  public void testLexingDquotedString() throws Exception {
    assertNoParsingErrorsIn(makeLfTargetCode("Python", "a = \" a string \""));
  }

  @Test
  public void testLexingMultilineString() throws Exception {
    assertNoParsingErrorsIn(makeLfTargetCode("Python", "a = \"\"\" a 'string' \"\"\""));
    assertNoParsingErrorsIn(makeLfTargetCode("Python", "a = \"\"\" a 'strin\ng' \"\"\""));
    assertNoParsingErrorsIn(makeLfTargetCode("Python", "a = \"\"\" \na 'string'\n \"\"\""));
  }

  @Test
  public void testLexingDquotedStringWithEscape() throws Exception {
    assertNoParsingErrorsIn(makeLfTargetCode("C", "printf(\"Hello World.\\n\");\n"));
  }

  @Test
  public void testLexingCharLiteral() throws Exception {
    assertNoParsingErrorsIn(makeLfTargetCode("C", "char c0 = 'c';"));
  }

  @Test
  public void testLexingEscapedCharLiteral() throws Exception {
    assertNoParsingErrorsIn(makeLfTargetCode("C", "char c0 = '\\n';"));
  }

  private String makeLfTargetCode(final String target, final String code) {
    return "target "
        + target
        + ";\n"
        + "reactor Foo {\n"
        + "    preamble {=\n"
        + "       "
        + code
        + "\n"
        + "    =}\n"
        + "}";
  }

  private void assertNoParsingErrorsIn(String source) throws Exception {
    List<Diagnostic> errors = doParse(source);
    Assertions.assertTrue(
        errors.isEmpty(), "Unexpected errors: " + IterableExtensions.join(errors, ", "));
  }

  private void expectParsingErrorIn(String source) throws Exception {
    List<Diagnostic> errors = doParse(source);
    Assertions.assertFalse(errors.isEmpty(), "Expected a parsing error, none occurred");
  }

  private List<Diagnostic> doParse(String source) throws Exception {
    Model result = parseHelper.parse(source);
    Assertions.assertNotNull(result);
    return result.eResource().getErrors();
  }
}