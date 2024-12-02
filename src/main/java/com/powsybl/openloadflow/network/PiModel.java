/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.apache.commons.lang3.Range;
import org.apache.commons.math3.complex.Complex;

import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface PiModel {

    double A2 = 0;

    double R2 = 1;

    double getR();

    PiModel setR(double r);

    double getX();

    PiModel setX(double x);

    double getZ();

    double getY();

    double getKsi();

    double getG1();

    double getB1();

    double getG2();

    double getB2();

    double getR1();

    double getMinR1();

    double getMaxR1();

    double getContinuousR1();

    double getA1();

    PiModel setA1(double a1);

    PiModel setR1(double r1);

    void roundA1ToClosestTap();

    void roundR1ToClosestTap();

    boolean shiftOneTapPositionToChangeA1(Direction direction);

    Optional<Direction> updateTapPositionToReachNewR1(double deltaR1, int maxTapShift, AllowedDirection allowedDirection);

    Optional<Direction> updateTapPositionToExceedNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection);

    Optional<Direction> updateTapPositionToReachNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection);

    Optional<Complex> getCutZ(double minZ, LoadFlowModel loadFlowModel);

    boolean setMinZ(double minZ, LoadFlowModel loadFlowModel);

    void setBranch(LfBranch branch);

    int getTapPosition();

    PiModel setTapPosition(int tapPosition);

    Range<Integer> getTapPositionRange();
}
