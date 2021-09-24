/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.util;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class ParticipatingElement {

    private final Object element;

    private double factor;

    public ParticipatingElement(Object element, double factor) {
        this.element = element;
        this.factor = factor;
    }

    public Object getElement() {
        return element;
    }

    public double getFactor() {
        return factor;
    }

    public void setFactor(double factor) {
        this.factor = factor;
    }

    public static void normalizeParticipationFactors(List<ParticipatingElement> participatingElements, String elementType) {
        double factorSum = participatingElements.stream()
                .mapToDouble(participatingGenerator -> participatingGenerator.factor)
                .sum();
        if (factorSum == 0) {
            throw new PowsyblException("No more " + elementType + " participating to slack distribution");
        }
        for (ParticipatingElement participatingElement : participatingElements) {
            participatingElement.factor /= factorSum;
        }
    }

    public LfBus getLfBus() {
        if (element instanceof LfGenerator) {
            return ((LfGenerator) element).getBus();
        } else if (element instanceof LfBus) {
            return (LfBus) element;
        } else {
            return null;
        }
    }
}

