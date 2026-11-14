package dev.tvcallalert.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** Interop against testvectors/vectors.tsv, generated independently with node:crypto. */
public class VectorsInteropTest {
    private static final String HEADER = "seedHex\tseedCode\ttopicDown\ttopicAck\ttype\tslot\tts"
            + "\tnonce\tref\tmessage\tackStatus\tackTs\tack";

    private static final class Row {
        byte[] seed;
        String seedCode;
        String topicDown;
        String topicAck;
        String type;
        int slot;
        long ts;
        String nonce;
        String ref;
        String message;
        String ackStatus;
        long ackTs;
        String ack;
    }

    private static List<Row> rows;

    /** Gradle runs with the working directory at core/, an IDE may use the repo root. */
    private static File locate() {
        List<File> tried = new ArrayList<>();
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File f = new File(dir, "testvectors/vectors.tsv");
            tried.add(f);
            if (f.isFile()) {
                return f;
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("vectors.tsv not found, tried " + tried);
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @BeforeClass
    public static void load() throws IOException {
        List<String> lines = Files.readAllLines(locate().toPath(), StandardCharsets.UTF_8);
        assertEquals(HEADER, lines.get(0).replace("\r", ""));
        rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).replace("\r", "");
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals("columns in row " + i, 13, f.length);
            Row r = new Row();
            r.seed = unhex(f[0]);
            r.seedCode = f[1];
            r.topicDown = f[2];
            r.topicAck = f[3];
            r.type = f[4];
            r.slot = Integer.parseInt(f[5]);
            r.ts = Long.parseLong(f[6]);
            r.nonce = f[7];
            r.ref = f[8];
            r.message = f[9];
            r.ackStatus = f[10];
            r.ackTs = Long.parseLong(f[11]);
            r.ack = f[12];
            rows.add(r);
        }
        assertTrue("vector file has rows", rows.size() >= 6);
    }

    @Test
    public void everyRow_base32MatchesSeedCode() {
        for (Row r : rows) {
            assertEquals(r.seedCode, Codec.base32(r.seed));
        }
    }

    @Test
    public void everyRow_formatSeedCodeIsGroupedSeedCode() {
        for (Row r : rows) {
            String formatted = Codec.formatSeedCode(r.seed);
            assertTrue(formatted, formatted.matches("([A-Z2-7]{4}-){6}[A-Z2-7]{2}"));
            assertEquals(r.seedCode, formatted.replace("-", ""));
        }
    }

    @Test
    public void everyRow_parseSeedCodeRoundTrips() {
        for (Row r : rows) {
            assertArrayEquals(r.seed, Codec.parseSeedCode(r.seedCode));
            assertArrayEquals(r.seed, Codec.parseSeedCode(Codec.formatSeedCode(r.seed)));
            assertArrayEquals(r.seed, Codec.parseSeedCode(r.seedCode.toLowerCase()));
        }
    }

    @Test
    public void everyRow_derivedTopicsMatch() {
        for (Row r : rows) {
            SlotKeys k = Kdf.derive(r.seed);
            assertEquals(r.topicDown, k.topicDown);
            assertEquals(r.topicAck, k.topicAck);
        }
    }

    @Test
    public void everyRow_messageParsesWithExpectedFields() {
        for (Row r : rows) {
            MessageParser.Result p = MessageParser.parse(r.message);
            assertNull(r.message, p.rejection);
            assertNotNull(p.message);
            assertEquals(r.type.toUpperCase(), p.message.type.name());
            assertEquals(r.slot, p.message.slot);
            assertEquals(r.ts, p.message.ts);
            assertEquals(r.nonce, p.message.nonce);
            assertEquals("-".equals(r.ref) ? null : r.ref, p.message.ref);
        }
    }

    @Test
    public void everyRow_pipelineAcceptsMessageOnTopicDownAtTs() {
        for (Row r : rows) {
            TestSupport.FakeStore store = new TestSupport.FakeStore();
            store.keys[r.slot] = Kdf.derive(r.seed);
            Decision d = new AlertPipeline(store).process(r.topicDown, r.message, r.ts);
            assertNull(r.message, d.rejection);
            assertTrue(d.authenticated);
            assertEquals(r.slot, d.slot);
            assertEquals(r.nonce, d.nonce);
            if ("alert".equals(r.type)) {
                assertEquals(Decision.Kind.SHOW, d.kind);
                assertNull(d.ref);
            } else if ("cancel".equals(r.type)) {
                assertEquals(Decision.Kind.CANCEL, d.kind);
                assertEquals(r.ref, d.ref);
            } else if ("ping".equals(r.type)) {
                assertEquals(Decision.Kind.PONG, d.kind);
            } else {
                fail("unknown type in vectors: " + r.type);
            }
            assertEquals(r.ts, store.highWater[r.slot]);
        }
    }

    @Test
    public void everyRow_pipelineRejectsMessageOnAckTopic() {
        for (Row r : rows) {
            TestSupport.FakeStore store = new TestSupport.FakeStore();
            store.keys[r.slot] = Kdf.derive(r.seed);
            Decision d = new AlertPipeline(store).process(r.topicAck, r.message, r.ts);
            assertEquals(Rejection.UNKNOWN_TOPIC, d.rejection);
        }
    }

    @Test
    public void everyRow_ackBuilderReproducesAckByteForByte() {
        for (Row r : rows) {
            AckBuilder.Status status = null;
            for (AckBuilder.Status s : AckBuilder.Status.values()) {
                if (s.wire.equals(r.ackStatus)) {
                    status = s;
                }
            }
            assertNotNull("status " + r.ackStatus, status);
            String ack = AckBuilder.build(status, r.slot, r.ackTs, r.nonce, Kdf.derive(r.seed));
            assertArrayEquals(r.ack.getBytes(StandardCharsets.UTF_8),
                    ack.getBytes(StandardCharsets.UTF_8));
        }
    }
}
