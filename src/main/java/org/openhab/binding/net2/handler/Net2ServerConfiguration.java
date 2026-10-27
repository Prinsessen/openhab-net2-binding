/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.net2.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration class for Net2 Server bridge.
 *
 * @author OpenHAB Community - Initial contribution
 */
@NonNullByDefault
public class Net2ServerConfiguration {
    public String hostname = "localhost";
    public Integer port = 8443;
    public String username = "";
    public String password = "";
    public String clientId = "";
    public Boolean tlsVerification = true;
    public Integer refreshInterval = 30;
    public Boolean autoReinstate = false;
    public Integer lockoutThreshold = 3;
    public Integer lockoutWindow = 10;
    public Integer reinstateInterval = 30;
}
