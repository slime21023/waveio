package io.wavejava.wave.api.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Body;
import io.wavejava.wave.api.http.BodyAlreadyConsumedException;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.render.ParseTarget;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class UrlEncodedFormParserTest {
    @Test
    void parsesPlusPercentEscapesRepeatedFieldsAndWireOrder() {
        var body = Body.utf8("name=Ada+Lovelace&tag=java&tag=web&city=%E5%8F%B0%E5%8C%97&empty=&flag", 128);
        var form = new UrlEncodedFormParser().parse(body);

        assertEquals(List.of("java", "web"), form.values("tag"));
        assertEquals("Ada Lovelace", form.first("name").orElseThrow());
        assertEquals("台北", form.first("city").orElseThrow());
        assertEquals("", form.first("empty").orElseThrow());
        assertEquals(List.of(
                new FormData.Entry("name", "Ada Lovelace"),
                new FormData.Entry("tag", "java"),
                new FormData.Entry("tag", "web"),
                new FormData.Entry("city", "台北"),
                new FormData.Entry("empty", ""),
                new FormData.Entry("flag", "")
        ), form.entries());
        assertThrows(UnsupportedOperationException.class, () -> form.values("tag").add("other"));
        assertThrows(UnsupportedOperationException.class, () -> form.asMap().put("other", List.of("x")));
        assertTrue(body.isConsumed());
    }

    @Test
    void rejectsMalformedPercentEscapesAndInvalidCharsetSequences() {
        var percentFailure = assertThrows(MalformedFormException.class,
                () -> new UrlEncodedFormParser().parse(Body.utf8("ok=x&bad=%Q0", 32)));
        assertEquals(MalformedFormException.Reason.INVALID_PERCENT_ESCAPE, percentFailure.reason());
        assertEquals(1, percentFailure.fieldIndex());

        var charsetFailure = assertThrows(MalformedFormException.class,
                () -> new UrlEncodedFormParser().parse(Body.utf8("bad=%C3%28", 32)));
        assertEquals(MalformedFormException.Reason.INVALID_CHARACTER_ENCODING, charsetFailure.reason());
    }

    @Test
    void consumesBodiesAndEnforcesEveryConfiguredBudget() {
        var byteLimited = Body.utf8("a=12345", 32);
        var byteFailure = assertThrows(FormLimitExceededException.class,
                () -> new UrlEncodedFormParser(new UrlEncodedFormParser.Limits(4, 8, 8, 8)).parse(byteLimited));
        assertEquals(FormLimitExceededException.Limit.BODY_BYTES, byteFailure.limit());
        assertTrue(byteLimited.isConsumed());
        assertThrows(BodyAlreadyConsumedException.class, byteLimited::bytes);

        var fieldFailure = assertThrows(FormLimitExceededException.class,
                () -> new UrlEncodedFormParser(new UrlEncodedFormParser.Limits(32, 1, 8, 8))
                        .parse(Body.utf8("a=1&b=2", 32)));
        assertEquals(FormLimitExceededException.Limit.FIELDS, fieldFailure.limit());

        var nameFailure = assertThrows(FormLimitExceededException.class,
                () -> new UrlEncodedFormParser(new UrlEncodedFormParser.Limits(32, 8, 2, 8))
                        .parse(Body.utf8("abc=1", 32)));
        assertEquals(FormLimitExceededException.Limit.FIELD_NAME_CHARACTERS, nameFailure.limit());

        var valueFailure = assertThrows(FormLimitExceededException.class,
                () -> new UrlEncodedFormParser(new UrlEncodedFormParser.Limits(32, 8, 8, 0))
                        .parse(Body.utf8("a=%F0%9F%98%80", 32)));
        assertEquals(FormLimitExceededException.Limit.FIELD_VALUE_CHARACTERS, valueFailure.limit());
    }

    @Test
    void honorsDeclaredCharsetAndParserContractWithoutConsumingWrongContentType() {
        var parser = new UrlEncodedFormParser();
        var target = ParseTarget.of(FormData.class);
        var latin1 = MediaType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.ISO_8859_1);

        assertTrue(parser.supports(target, latin1));
        assertFalse(parser.supports(target, MediaType.APPLICATION_JSON));
        assertEquals("café", parser.parse(Body.utf8("name=caf%E9", 32), target, latin1)
                .first("name").orElseThrow());

        var wrongType = Body.utf8("name=wave", 32);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(wrongType, MediaType.APPLICATION_JSON));
        assertFalse(wrongType.isConsumed());
    }

    @Test
    void limitsRejectNegativeBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new UrlEncodedFormParser.Limits(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UrlEncodedFormParser.Limits(0, -1, 0, 0));
    }
}
