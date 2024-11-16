/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.Evaluable;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface LfElement extends PropertyBag {

    String getId();

    List<String> getOriginalIds();

    String getMainOriginalId();

    ElementType getType();

    int getNum();

    void setNum(int num);

    boolean isDisabled();

    void setDisabled(boolean disabled);

    LfNetwork getNetwork();

    void removeEvaluable(Evaluable evaluable);

    void setVoltageControlEnabled(boolean enabled);
}
