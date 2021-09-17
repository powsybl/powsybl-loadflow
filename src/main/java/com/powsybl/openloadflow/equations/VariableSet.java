/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class VariableSet<V extends Enum<V> & Quantity> {

    private final Map<Pair<Integer, V>, Variable<V>> variables = new HashMap<>();

    public Variable<V> getVariable(int num, V type) {
        return variables.computeIfAbsent(Pair.of(num, type), p -> new Variable<>(p.getLeft(), p.getRight()));
    }
}
