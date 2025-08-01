/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.compressors.bzip2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Testcase porting a test from Python's testsuite.
 *
 * @see "https://issues.apache.org/jira/browse/COMPRESS-253"
 */
class PythonTruncatedBzip2Test {

    // @formatter:off
    private static final String TEXT = "root:x:0:0:root:/root:/bin/bash\nbin:x:1:1:bin:/bin:\ndaemon:x:2:2:daemon:/sbin:\nadm:x:3:4:adm:/var/adm:\nlp:x:4:7:"
            + "lp:/var/spool/lpd:\nsync:x:5:0:sync:/sbin:/bin/sync\nshutdown:x:6:0:shutdown:/sbin:/sbin/shutdown\nhalt:x:7:0:halt:/sbin:/sbin/halt\nmail:x:8"
            + ":12:mail:/var/spool/mail:\nnews:x:9:13:news:/var/spool/news:\nuucp:x:10:14:uucp:/var/spool/uucp:\noperator:x:11:0:operator:/root:\ngames:x:12"
            + ":100:games:/usr/games:\ngopher:x:13:30:gopher:/usr/lib/gopher-data:\nftp:x:14:50:FTP User:/var/ftp:/bin/bash\nnobody:x:65534:65534:Nobody:"
            + "/home:\npostfix:x:100:101:postfix:/var/spool/postfix:\nniemeyer:x:500:500::/home/niemeyer:/bin/bash\npostgres:x:101:102:PostgreSQL Server:"
            + "/var/lib/pgsql:/bin/bash\nmysql:x:102:103:MySQL server:/var/lib/mysql:/bin/bash\nwww:x:103:104::/var/www:/bin/false\n";
    // @formatter:on

    private static byte[] DATA;
    private static byte[] TRUNCATED_DATA;

    @BeforeAll
    public static void initializeTestData() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bz2out = new BZip2CompressorOutputStream(out)) {
            bz2out.write(TEXT.getBytes(), 0, TEXT.getBytes().length);
        }
        DATA = out.toByteArray();

        // Drop the eos_magic field (6 bytes) and CRC (4 bytes).
        TRUNCATED_DATA = Arrays.copyOfRange(DATA, 0, DATA.length - 10);
    }

    @SuppressWarnings("resource") // Caller closes
    private static ReadableByteChannel makeBZ2C(final InputStream source) throws IOException {
        final BufferedInputStream bin = new BufferedInputStream(source);
        final BZip2CompressorInputStream bZin = new BZip2CompressorInputStream(bin, true);
        return Channels.newChannel(bZin);
    }

    private ReadableByteChannel bz2Channel;

    @AfterEach
    public void closeChannel() throws IOException {
        bz2Channel.close();
        bz2Channel = null;
    }

    @BeforeEach
    public void initializeChannel() throws IOException {
        this.bz2Channel = makeBZ2C(new ByteArrayInputStream(TRUNCATED_DATA));
    }

    @Test
    void testPartialReadTruncatedData() throws IOException {
        // with BZ2File(self.filename) as f:
        // self.assertEqual(f.read(len(self.TEXT)), self.TEXT)
        // self.assertRaises(EOFError, f.read, 1)

        final int length = TEXT.length();
        final ByteBuffer buffer1 = ByteBuffer.allocate(length);
        bz2Channel.read(buffer1);

        assertArrayEquals(Arrays.copyOfRange(TEXT.getBytes(), 0, length), buffer1.array());

        // subsequent read should throw
        final ByteBuffer buffer2 = ByteBuffer.allocate(1);
        assertThrows(IOException.class, () -> bz2Channel.read(buffer2), "The read should have thrown.");
    }

    @Test
    void testTruncatedData() {
        // with BZ2File(self.filename) as f:
        // self.assertRaises(EOFError, f.read)
        final ByteBuffer buffer = ByteBuffer.allocate(8192);
        assertThrows(IOException.class, () -> bz2Channel.read(buffer));
    }
}
