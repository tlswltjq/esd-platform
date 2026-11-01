package com.stove.archfixture.precision.adapter;

import com.stove.archfixture.precision.core.port.pg.RemoteRows;

public class RowsClient implements RemoteRows {
    @Override public String read() { return "remote"; }
}
