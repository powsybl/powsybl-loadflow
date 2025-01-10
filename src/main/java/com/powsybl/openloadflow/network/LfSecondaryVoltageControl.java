/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfSecondaryVoltageControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfSecondaryVoltageControl.class);

    private final String zoneName;

    private final LfBus pilotBus;

    private final Set<String> participatingControlUnitIds;

    private final Set<GeneratorVoltageControl> generatorVoltageControls;

    private double targetValue;

    public LfSecondaryVoltageControl(String zoneName, LfBus pilotBus, double targetValue, Set<String> participatingControlUnitIds,
                                     Set<GeneratorVoltageControl> generatorVoltageControls) {
        this.zoneName = Objects.requireNonNull(zoneName);
        this.pilotBus = Objects.requireNonNull(pilotBus);
        this.targetValue = targetValue;
        this.participatingControlUnitIds = Objects.requireNonNull(participatingControlUnitIds);
        this.generatorVoltageControls = Objects.requireNonNull(generatorVoltageControls);
    }

    public String getZoneName() {
        return zoneName;
    }

    public double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(double targetValue) {
        if (this.targetValue != targetValue) {
            this.targetValue = targetValue;
            tryToReEnableHelpfulControllerBuses();
        }
    }

    public LfBus getPilotBus() {
        return pilotBus;
    }

    public void addParticipatingControlUnit(String id) {
        if (participatingControlUnitIds.add(id)) {
            tryToReEnableHelpfulControllerBuses();
        }
    }

    public void removeParticipatingControlUnit(String id) {
        if (participatingControlUnitIds.remove(id)) {
            tryToReEnableHelpfulControllerBuses();
        }
    }

    public Set<GeneratorVoltageControl> getGeneratorVoltageControls() {
        return generatorVoltageControls.stream()
                .filter(this::hasAtLeastOneParticipatingControlUnit) // only keep voltage controls where there is at list one enabled control unit
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean hasAtLeastOneParticipatingControlUnit(GeneratorVoltageControl vc) {
        for (var controllerElement : vc.getMergedControllerElements()) {
            for (LfGenerator generator : controllerElement.getGenerators()) {
                if (participatingControlUnitIds.contains(generator.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<LfBus> findControllerBuses(LfBus controlledBus) {
        return controlledBus.getGeneratorVoltageControl().orElseThrow()
                .getMergedControllerElements().stream()
                .filter(controllerBus -> !controllerBus.isDisabled())
                .toList();
    }

    public List<LfBus> getControlledBuses() {
        return getGeneratorVoltageControls().stream()
                .filter(voltageControl -> voltageControl.isVisible() && voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .map(VoltageControl::getControlledBus)
                .toList();
    }

    private static Optional<LfBus> findAnyControllerBusWithVoltageControlEnabled(LfBus controlledBus) {
        return findControllerBuses(controlledBus).stream()
                .filter(LfBus::isGeneratorVoltageControlEnabled)
                .findAny();
    }

    public Optional<LfBus> findAnyControlledBusWithAtLeastOneControllerBusWithVoltageControlEnabled() {
        return getControlledBuses().stream()
                .flatMap(controlledBus -> findAnyControllerBusWithVoltageControlEnabled(controlledBus).stream())
                .findAny();
    }

    public List<LfBus> getControllerBuses() {
        return getControlledBuses().stream()
                .flatMap(controlledBus -> findControllerBuses(controlledBus).stream())
                .toList();
    }

    public List<LfBus> getEnabledControllerBuses() {
        return getControllerBuses().stream()
                .filter(LfBus::isGeneratorVoltageControlEnabled)
                .toList();
    }

    public void tryToReEnableHelpfulControllerBuses() {
        List<LfBus> controllerBusesToMinQ = new ArrayList<>();
        List<LfBus> controllerBusesToMaxQ = new ArrayList<>();
        List<LfBus> allControllerBuses = new ArrayList<>();
        classifyControllerBuses(allControllerBuses, controllerBusesToMinQ, controllerBusesToMaxQ);

        List<LfBus> controllerBusesToLimit = new ArrayList<>(controllerBusesToMinQ.size() + controllerBusesToMaxQ.size());
        controllerBusesToLimit.addAll(controllerBusesToMinQ);
        controllerBusesToLimit.addAll(controllerBusesToMaxQ);
        // if there is at least one of the generator to the limit but not all, re-enable all generators to help
        // the secondary voltage control to get a reactive power alignment
        if (!controllerBusesToLimit.isEmpty() && controllerBusesToLimit.size() < allControllerBuses.size()) {
            for (LfBus controllerBus : controllerBusesToLimit) {
                controllerBus.setGeneratorVoltageControlEnabled(true);
                controllerBus.setQLimitType(null);
            }
            LOGGER.debug("Secondary voltage control of zone '{}': controller buses {} have been re-enabled because might help to reach pilot bus target",
                    getZoneName(), controllerBusesToLimit);
        }
    }

    private void classifyControllerBuses(List<LfBus> allControllerBuses, List<LfBus> controllerBusesToMinQ, List<LfBus> controllerBusesToMaxQ) {
        getControllerBuses()
                .forEach(controllerBus -> {
                    allControllerBuses.add(controllerBus);
                    controllerBus.getQLimitType().ifPresent(qLimitType -> {
                        if (qLimitType == LfBus.QLimitType.MIN_Q) {
                            controllerBusesToMinQ.add(controllerBus);
                        } else { // MAX_Q
                            controllerBusesToMaxQ.add(controllerBus);
                        }
                    });
                });
    }
}
