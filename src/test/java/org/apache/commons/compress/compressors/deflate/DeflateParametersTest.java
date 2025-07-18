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
package org.apache.commons.compress.compressors.deflate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DeflateParametersTest {

    @Test
    void testShouldBeAbleToSetCompressionLevel() {
        final DeflateParameters p = new DeflateParameters();
        p.setCompressionLevel(5);
        assertEquals(5, p.getCompressionLevel());
    }

    @Test
    void testShouldNotBeAbleToSetCompressionLevelToADoubleDigitValue() {
        final DeflateParameters p = new DeflateParameters();
        assertThrows(IllegalArgumentException.class, () -> p.setCompressionLevel(DeflateParameters.MAX_LEVEL + 1));
    }

    @Test
    void testShouldNotBeAbleToSetCompressionLevelToANegativeValue() {
        final DeflateParameters p = new DeflateParameters();
        assertThrows(IllegalArgumentException.class, () -> p.setCompressionLevel(DeflateParameters.MIN_LEVEL - 1));
    }
}
