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
import com.powsybl.openloadflow.lf.outerloop.OuterLoop;
import org.apache.commons.compress.utils.Lists;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public interface OuterLoopConfig<O extends OuterLoop<?, ?, ?, ?, ?>> {
    List<O> configure(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt);

    static <C extends OuterLoopConfig<?>> Optional<C> findOuterLoopConfig(Class<C> configClass) {
        List<C> outerLoopConfigs = Lists.newArrayList(ServiceLoader.load(configClass, configClass.getClassLoader()).iterator());
        if (outerLoopConfigs.isEmpty()) {
            return Optional.empty();
        } else {
            if (outerLoopConfigs.size() > 1) {
                throw new PowsyblException("Only one outer loop config is expected on class path");
            }
            return Optional.of(outerLoopConfigs.get(0));
        }
    }
}
