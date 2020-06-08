/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public enum EquationType {
    BUS_P("p"),
    BUS_Q("q"),
    BUS_V("v"),
    BUS_PHI("\u03C6"),
    BRANCH_P("t"),
    BRANCH_I("i"),
    ZERO_Q("z_q"),
    ZERO_V("z_v"),
    ZERO_PHI("z_\u03C6");

    private final String symbol;

    EquationType(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
