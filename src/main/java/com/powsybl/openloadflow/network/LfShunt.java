/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.Evaluable;
import org.apache.commons.lang3.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface LfShunt extends LfElement {

    Logger LOGGER = LoggerFactory.getLogger(LfShunt.class);

    class Controller {

        private final String id;

        private final List<Double> sectionsB;

        private final List<Double> sectionsG;

        private int position;

        private final double bMagnitude;

        public Controller(String id, List<Double> sectionsB, List<Double> sectionsG, int position) {
            this.id = Objects.requireNonNull(id);
            this.sectionsB = Objects.requireNonNull(sectionsB);
            this.sectionsG = Objects.requireNonNull(sectionsG);
            this.position = position;
            double bMin = Math.min(sectionsB.get(0), sectionsB.get(sectionsB.size() - 1));
            double bMax = Math.max(sectionsB.get(0), sectionsB.get(sectionsB.size() - 1));
            this.bMagnitude = Math.abs(bMax - bMin);
        }

        public String getId() {
            return id;
        }

        public List<Double> getSectionsB() {
            return sectionsB;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public double getB() {
            return sectionsB.get(this.position);
        }

        public double getG() {
            return sectionsG.get(this.position);
        }

        public double getBMagnitude() {
            return bMagnitude;
        }

        private Range<Integer> getAllowedPositionRange(AllowedDirection allowedDirection) {
            return switch (allowedDirection) {
                case INCREASE -> Range.of(position, sectionsB.size() - 1);
                case DECREASE -> Range.of(0, position);
                case BOTH -> Range.of(0, sectionsB.size() - 1);
            };
        }

        public Optional<Direction> updateSectionB(double deltaB, int maxSectionShift, AllowedDirection allowedDirection) {
            // an increase allowed direction means that the section could increase.
            // a decrease allowed direction means that the section could decrease.
            double newB = getB() + deltaB;
            Range<Integer> positionRange = getAllowedPositionRange(allowedDirection);

            int oldSection = position;
            // find section with the closest b value without exceeding the maximum of sections to switch.
            double smallestDistance = Math.abs(deltaB);
            for (int p = positionRange.getMinimum(); p <= positionRange.getMaximum(); p++) {
                if (Math.abs(p - oldSection) > maxSectionShift) {
                    // we are not allowed in one outer loop run to go further than maxSectionShift sections
                    continue;
                }
                double distance = Math.abs(newB - sectionsB.get(p));
                if (distance < smallestDistance) {
                    position = p;
                    smallestDistance = distance;
                }
            }

            boolean hasChanged = position != oldSection;
            if (hasChanged) {
                LOGGER.debug("Controller '{}' change section from {} to {}", id, oldSection, position);
                return Optional.of(position - oldSection > 0 ? Direction.INCREASE : Direction.DECREASE);
            }
            return Optional.empty();
        }

        public void updateSectionB(int newPosition) {
            position = newPosition;
        }
    }

    LfBus getBus();

    double getB();

    default double getBMagnitude() {
        return Math.abs(getB());
    }

    void setB(double b);

    double dispatchB();

    double getG();

    void setG(double g);

    void updateState(LfNetworkStateUpdateParameters parameters);

    boolean hasVoltageControlCapability();

    void setVoltageControlCapability(boolean voltageControlCapability);

    boolean isVoltageControlEnabled();

    void setVoltageControlEnabled(boolean voltageControlEnabled);

    Optional<ShuntVoltageControl> getVoltageControl();

    void setVoltageControl(ShuntVoltageControl voltageControl);

    void reInit();

    List<Controller> getControllers();

    Evaluable getP();

    void setP(Evaluable evaluable);

    Evaluable getQ();

    void setQ(Evaluable evaluable);
}
