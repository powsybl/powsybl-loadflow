/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.Variable;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 */
public interface LinearEquationTerm extends EquationTerm<VariableType, EquationType> {

    double getCoefficient(Variable<VariableType> variable);
}
