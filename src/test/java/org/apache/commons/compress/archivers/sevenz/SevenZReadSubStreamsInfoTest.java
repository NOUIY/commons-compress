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

package org.apache.commons.compress.archivers.sevenz;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.apache.commons.compress.archivers.ArchiveException;
import org.junit.jupiter.api.Test;

class SevenZReadSubStreamsInfoTest {

    @Test
    void testReadSubStreamsInfo() throws IOException {
        assertThrows(ArchiveException.class,
                () -> SevenZFile.builder().setPath("src/test/resources/org/apache/commons/compress/sevenz/readSubStreamsInfo.bin").get().close());
    }
}
