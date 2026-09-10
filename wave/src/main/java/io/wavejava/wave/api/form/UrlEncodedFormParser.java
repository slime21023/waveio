package io.wavejava.wave.api.form;

import io.wavejava.wave.api.http.Body;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.render.ParseTarget;
import io.wavejava.wave.api.render.Parser;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Parses bounded {@code application/x-www-form-urlencoded} request bodies.
 *
 * <p>The parser uses UTF-8 unless a caller supplies an explicit charset or declared media type.
 * It decodes {@code +} as a space, percent escapes as raw encoded bytes, and rejects incomplete,
 * non-hex, or invalid-character-encoding input. Every body passed to a parse method is consumed
 * exactly once, including when a configured byte or field limit is exceeded.</p>
 */
public final class UrlEncodedFormParser implements Parser<FormData> {
    /** Conservative defaults for independently parsing a bounded aggregated body. */
    public static final Limits DEFAULT_LIMITS = new Limits(64 * 1024, 128, 256, 8 * 1024);

    private static final ParseTarget<FormData> FORM_DATA_TARGET = ParseTarget.of(FormData.class);

    private final Limits limits;

    /** Creates a parser with {@link #DEFAULT_LIMITS}. */
    public UrlEncodedFormParser() {
        this(DEFAULT_LIMITS);
    }

    /** Creates a parser with explicit count, byte, and decoded-character budgets. */
    public UrlEncodedFormParser(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Returns this parser's immutable limits. */
    public Limits limits() {
        return limits;
    }

    /** Parses a UTF-8 URL-encoded form. */
    public FormData parse(Body body) {
        return parse(body, StandardCharsets.UTF_8);
    }

    /** Parses a URL-encoded form with an explicit charset. */
    public FormData parse(Body body, Charset charset) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(charset, "charset");
        var encoded = body.bytes();
        if (encoded.length > limits.maximumBytes()) {
            throw new FormLimitExceededException(
                    FormLimitExceededException.Limit.BODY_BYTES, encoded.length, limits.maximumBytes());
        }
        return parseEncoded(encoded, charset);
    }

    /**
     * Validates the declared form media type and parses with its declared charset, if present.
     * A non-form media type is rejected before the body is consumed.
     */
    public FormData parse(Body body, MediaType mediaType) {
        Objects.requireNonNull(mediaType, "mediaType");
        if (!isUrlEncoded(mediaType)) {
            throw new IllegalArgumentException("Expected application/x-www-form-urlencoded but was " + mediaType);
        }
        return parse(body, mediaType.charset().orElse(StandardCharsets.UTF_8));
    }

    @Override
    public boolean supports(ParseTarget<FormData> target, MediaType mediaType) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(mediaType, "mediaType");
        return target.rawType().equals(FormData.class) && isUrlEncoded(mediaType);
    }

    /** Parses with UTF-8 when invoked through the general parser contract. */
    @Override
    public FormData parse(Body body, ParseTarget<FormData> target) {
        requireFormDataTarget(target);
        return parse(body);
    }

    /** Parses with the declared charset when invoked through the general parser contract. */
    @Override
    public FormData parse(Body body, ParseTarget<FormData> target, MediaType mediaType) {
        requireFormDataTarget(target);
        return parse(body, mediaType);
    }

    private FormData parseEncoded(byte[] encoded, Charset charset) {
        if (encoded.length == 0) {
            return FormData.empty();
        }

        var entries = new ArrayList<FormData.Entry>();
        var fieldStart = 0;
        var fieldIndex = 0;
        for (var position = 0; position <= encoded.length; position++) {
            if (position != encoded.length && encoded[position] != '&') {
                continue;
            }
            var actualFieldCount = fieldIndex + 1L;
            if (actualFieldCount > limits.maximumFields()) {
                throw new FormLimitExceededException(
                        FormLimitExceededException.Limit.FIELDS, actualFieldCount, limits.maximumFields());
            }

            var equals = firstEquals(encoded, fieldStart, position);
            var nameEnd = equals < 0 ? position : equals;
            var valueStart = equals < 0 ? position : equals + 1;
            var name = decodeComponent(encoded, fieldStart, nameEnd, fieldIndex, charset);
            var value = decodeComponent(encoded, valueStart, position, fieldIndex, charset);
            enforceCharacterLimit(FormLimitExceededException.Limit.FIELD_NAME_CHARACTERS,
                    name.codePointCount(0, name.length()), limits.maximumFieldNameCharacters());
            enforceCharacterLimit(FormLimitExceededException.Limit.FIELD_VALUE_CHARACTERS,
                    value.codePointCount(0, value.length()), limits.maximumFieldValueCharacters());
            entries.add(new FormData.Entry(name, value));
            fieldIndex++;
            fieldStart = position + 1;
        }
        return FormData.fromEntries(entries);
    }

    private static int firstEquals(byte[] input, int start, int end) {
        for (var index = start; index < end; index++) {
            if (input[index] == '=') {
                return index;
            }
        }
        return -1;
    }

    private static String decodeComponent(byte[] input, int start, int end, int fieldIndex, Charset charset) {
        var decoded = new ByteArrayOutputStream(end - start);
        for (var index = start; index < end; index++) {
            var value = input[index] & 0xff;
            if (value == '+') {
                decoded.write(' ');
            } else if (value == '%') {
                if (index + 2 >= end) {
                    throw malformedPercent(fieldIndex, index, "percent escape is incomplete");
                }
                var high = hexValue(input[index + 1]);
                var low = hexValue(input[index + 2]);
                if (high < 0 || low < 0) {
                    throw malformedPercent(fieldIndex, index, "percent escape must use two hexadecimal digits");
                }
                decoded.write((high << 4) | low);
                index += 2;
            } else {
                decoded.write(value);
            }
        }

        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new MalformedFormException(MalformedFormException.Reason.INVALID_CHARACTER_ENCODING,
                    fieldIndex, start, "value is not valid " + charset.name(), failure);
        }
    }

    private static int hexValue(byte value) {
        var character = value & 0xff;
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    private static MalformedFormException malformedPercent(int fieldIndex, int byteOffset, String detail) {
        return new MalformedFormException(MalformedFormException.Reason.INVALID_PERCENT_ESCAPE,
                fieldIndex, byteOffset, detail, null);
    }

    private static void enforceCharacterLimit(FormLimitExceededException.Limit limit, int actual, int maximum) {
        if (actual > maximum) {
            throw new FormLimitExceededException(limit, actual, maximum);
        }
    }

    private static boolean isUrlEncoded(MediaType mediaType) {
        return mediaType.type().equals(MediaType.APPLICATION_FORM_URLENCODED.type())
                && mediaType.subtype().equals(MediaType.APPLICATION_FORM_URLENCODED.subtype());
    }

    private static void requireFormDataTarget(ParseTarget<FormData> target) {
        Objects.requireNonNull(target, "target");
        if (!target.rawType().equals(FORM_DATA_TARGET.rawType())) {
            throw new IllegalArgumentException("UrlEncodedFormParser only parses FormData, not " + target);
        }
    }

    /**
     * Bounded resources consumed by one URL-encoded form parse.
     *
     * <p>{@code maximumFields} counts each ampersand-separated field occurrence, including
     * repeated names and empty components. Character limits count decoded Unicode code points.</p>
     */
    public record Limits(
            long maximumBytes,
            int maximumFields,
            int maximumFieldNameCharacters,
            int maximumFieldValueCharacters
    ) {
        public Limits {
            if (maximumBytes < 0) {
                throw new IllegalArgumentException("maximumBytes must not be negative");
            }
            if (maximumFields < 0) {
                throw new IllegalArgumentException("maximumFields must not be negative");
            }
            if (maximumFieldNameCharacters < 0) {
                throw new IllegalArgumentException("maximumFieldNameCharacters must not be negative");
            }
            if (maximumFieldValueCharacters < 0) {
                throw new IllegalArgumentException("maximumFieldValueCharacters must not be negative");
            }
        }
    }
}
