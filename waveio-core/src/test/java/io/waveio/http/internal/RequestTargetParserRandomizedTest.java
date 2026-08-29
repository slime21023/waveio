package io.waveio.http.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RequestTargetParserRandomizedTest {
    private static final long SEED = 0x57415645494fL;
    private static final String ALPHABET = "abcXYZ019-_~ é中🙂";

    @Test
    void roundTripsOneThousandStrictUtf8SegmentsWithFixedSeed() {
        var random = new Random(SEED);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            int segments = 1 + random.nextInt(5);
            var encoded = new StringBuilder();
            var decoded = new StringBuilder();
            for (int segment = 0; segment < segments; segment++) {
                String value = "s" + randomValue(random, 1 + random.nextInt(12));
                encoded.append('/').append(percentEncode(value));
                decoded.append('/').append(value);
            }
            assertEquals(decoded.toString(), RequestTargetParser.parse(encoded.toString()).path(),
                    () -> "seed=" + SEED + ", target=" + encoded);
        }
    }

    @Test
    void rejectsRandomMalformedEscapesAndEncodedSeparatorsWithFixedSeed() {
        var random = new Random(SEED ^ 0xBADL);
        String[] forbidden = { "%", "%0", "%GG", "%2F", "%2f", "%5C", "%5c",
                "%C3%28", "%00", "%1F" };
        for (int iteration = 0; iteration < 1_000; iteration++) {
            String target = "/safe/s" + random.nextInt(10_000)
                    + forbidden[random.nextInt(forbidden.length)];
            assertThrows(IllegalArgumentException.class,
                    () -> RequestTargetParser.parse(target),
                    () -> "seed=" + (SEED ^ 0xBADL) + ", target=" + target);
        }
    }

    private static String randomValue(Random random, int length) {
        var result = new StringBuilder();
        int[] points = ALPHABET.codePoints().toArray();
        for (int index = 0; index < length; index++) {
            result.appendCodePoint(points[random.nextInt(points.length)]);
        }
        return result.toString();
    }

    private static String percentEncode(String value) {
        var result = new StringBuilder();
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte valueByte : bytes) {
            int unsigned = valueByte & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '_' || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%');
                result.append(Character.forDigit(unsigned >>> 4, 16));
                result.append(Character.forDigit(unsigned & 0xf, 16));
            }
        }
        return result.toString();
    }
}
