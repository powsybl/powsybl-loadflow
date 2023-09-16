/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import java.util.List;
import java.util.Map;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public interface LfLoad extends PropertyBag {

    String getId();

    LfBus getBus();

    LfLoadModel getLoadModel();

    double getInitialTargetP();

    double getTargetP();

    void setTargetP(double targetP);

    double getTargetQ();

    void setTargetQ(double targetQ);

    boolean ensurePowerFactorConstantByLoad();

    double getAbsVariableTargetP();

    void setAbsVariableTargetP(double absVariableTargetP);

    double calculateNewTargetQ(double diffTargetP);

    List<String> getOriginalIds();

    double getOriginalLoadCount();

    boolean isOriginalLoadDisabled(String originalId);

    void setOriginalLoadDisabled(String originalId, boolean disabled);

    Map<String, Boolean> getOriginalLoadsDisablingStatus();

    void setOriginalLoadsDisablingStatus(Map<String, Boolean> originalLoadsDisablingStatus);

    void updateState(double diffLoadTargetP, boolean loadPowerFactorConstant, boolean breakers);
}
