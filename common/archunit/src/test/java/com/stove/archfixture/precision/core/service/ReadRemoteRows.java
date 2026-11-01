package com.stove.archfixture.precision.core.service;

import com.stove.archfixture.precision.core.port.pg.RemoteRows;

public class ReadRemoteRows implements RemoteRows {
    @Override public String read() { return "remote"; }
}
