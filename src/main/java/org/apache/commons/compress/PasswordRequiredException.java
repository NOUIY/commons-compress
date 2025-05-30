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

/**
 * Exception thrown when trying to read an encrypted entry or file without configuring a password.
 *
 * @since 1.10
 */
public class PasswordRequiredException extends CompressException {

    private static final long serialVersionUID = 1391070005491684483L;

    /**
     * Constructs a new exception.
     *
     * @param name name of the archive containing encrypted streams or the encrypted file.
     */
    public PasswordRequiredException(final String name) {
        super("Cannot read encrypted content from " + name + " without a password.");
    }
}
