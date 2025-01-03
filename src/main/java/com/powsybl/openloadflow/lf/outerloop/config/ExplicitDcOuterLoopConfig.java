/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop.config;

import com.powsybl.commons.PowsyblException;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.lf.outerloop.AbstractAreaInterchangeControlOuterLoop;
import com.powsybl.openloadflow.lf.outerloop.AbstractIncrementalPhaseControlOuterLoop;
import com.powsybl.openloadflow.dc.DcOuterLoop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class ExplicitDcOuterLoopConfig extends AbstractDcOuterLoopConfig {

    public static final List<String> NAMES = List.of(AbstractIncrementalPhaseControlOuterLoop.NAME,
                                                        AbstractAreaInterchangeControlOuterLoop.NAME);

    private static Optional<DcOuterLoop> createOuterLoop(String name, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        return switch (name) {
            case AbstractIncrementalPhaseControlOuterLoop.NAME -> createIncrementalPhaseControlOuterLoop(parameters);
            case AbstractAreaInterchangeControlOuterLoop.NAME -> createAreaInterchangeControlOuterLoop(parameters, parametersExt);
            default -> throw new PowsyblException("Unknown outer loop '" + name + "' for DC load flow");
        };
    }

    @Override
    public List<DcOuterLoop> configure(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        return Objects.requireNonNull(parametersExt.getOuterLoopNames()).stream()
                .flatMap(name -> createOuterLoop(name, parameters, parametersExt).stream())
                .toList();
    }
}
