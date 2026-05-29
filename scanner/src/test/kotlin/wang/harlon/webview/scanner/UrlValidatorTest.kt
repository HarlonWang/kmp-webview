package wang.harlon.webview.scanner

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun acceptsHttpsUrl() {
        assertEquals("https://example.com", UrlValidator.validate("https://example.com"))
    }

    @Test
    fun acceptsHttpUrlWithPathAndQuery() {
        assertEquals(
            "http://example.com/path?q=1",
            UrlValidator.validate("http://example.com/path?q=1"),
        )
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("https://example.com", UrlValidator.validate("  https://example.com  "))
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals("HTTPS://example.com", UrlValidator.validate("HTTPS://example.com"))
    }

    @Test
    fun rejectsJavascriptScheme() {
        assertNull(UrlValidator.validate("javascript:alert(1)"))
    }

    @Test
    fun rejectsFileScheme() {
        assertNull(UrlValidator.validate("file:///etc/passwd"))
    }

    @Test
    fun rejectsTelScheme() {
        assertNull(UrlValidator.validate("tel:10086"))
    }

    @Test
    fun rejectsFtpScheme() {
        assertNull(UrlValidator.validate("ftp://example.com"))
    }

    @Test
    fun rejectsBareDomainWithoutScheme() {
        assertNull(UrlValidator.validate("example.com"))
    }

    @Test
    fun rejectsSchemeWithoutHost() {
        assertNull(UrlValidator.validate("https://"))
    }

    @Test
    fun rejectsPlainText() {
        assertNull(UrlValidator.validate("just some text"))
    }

    @Test
    fun rejectsEmpty() {
        assertNull(UrlValidator.validate(""))
    }

    @Test
    fun rejectsNull() {
        assertNull(UrlValidator.validate(null))
    }
}
