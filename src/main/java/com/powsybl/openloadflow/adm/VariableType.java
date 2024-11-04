/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.network.ElementType;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 */
public enum VariableType implements Quantity {
    BUS_VR("vr"), // real part
    BUS_VI("vi"); // imaginary part

    private final String symbol;

    VariableType(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String getSymbol() {
        return symbol;
    }

    @Override
    public ElementType getElementType() {
        return ElementType.BUS;
    }
}
