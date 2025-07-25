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

package org.apache.commons.compress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test that can read various archive file examples.
 *
 * This is a very simple implementation.
 *
 * Files must be in resources/archives, and there must be a file.txt containing the list of files in the archives.
 */
class ArchiveReadTest extends AbstractTest {

    private static final ClassLoader CLASS_LOADER = ArchiveReadTest.class.getClassLoader();
    private static final File ARC_DIR;
    private static final ArrayList<String> FILE_LIST = new ArrayList<>();

    static {
        try {
            ARC_DIR = new File(CLASS_LOADER.getResource("archives").toURI());
        } catch (final URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    public static Stream<Arguments> data() {
        assertTrue(ARC_DIR.exists());
        final Collection<Arguments> params = new ArrayList<>();
        for (final String fileName : ARC_DIR.list((dir, name) -> !name.endsWith(".txt"))) {
            params.add(Arguments.of(new File(ARC_DIR, fileName)));
        }
        return params.stream();
    }

    @BeforeAll
    public static void setUpFileList() throws Exception {
        assertTrue(ARC_DIR.exists());
        final File listing = new File(ARC_DIR, "files.txt");
        assertTrue(listing.canRead(), "files.txt is readable");
        try (BufferedReader br = new BufferedReader(Files.newBufferedReader(listing.toPath()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("#")) {
                    FILE_LIST.add(line);
                }
            }
        }
    }

    // files.txt contains size and file name
    @Override
    protected String getExpectedString(final ArchiveEntry entry) {
        return entry.getSize() + " " + entry.getName();
    }

    @ParameterizedTest
    @MethodSource("data")
    void testArchive(final File file) throws Exception {
        @SuppressWarnings("unchecked") // fileList is correct type already
        final ArrayList<String> expected = (ArrayList<String>) FILE_LIST.clone();
        assertDoesNotThrow(() -> checkArchiveContent(file, expected), "Problem checking " + file);
    }
}
