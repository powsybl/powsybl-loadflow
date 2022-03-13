/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractEquationSystemIndex<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemIndex<V, E> {

    protected final List<EquationSystemIndexListener> listeners = new ArrayList<>();

    @Override
    public void addListener(EquationSystemIndexListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void removeListener(EquationSystemIndexListener listener) {
        listeners.remove(Objects.requireNonNull(listener));
    }

    protected void notifyEquationChange() {
        listeners.forEach(EquationSystemIndexListener::onEquationChange);
    }

    protected void notifyVariableChange() {
        listeners.forEach(EquationSystemIndexListener::onVariableChange);
    }
}
