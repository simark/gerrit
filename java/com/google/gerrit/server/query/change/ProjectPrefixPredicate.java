// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.query.change;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.index.change.ChangeField;

public class ProjectPrefixPredicate extends ChangeIndexPredicate {
  public ProjectPrefixPredicate(String prefix) {
    super(ChangeField.PROJECTS, prefix);
  }

  @Override
  public boolean match(ChangeData object) {
    Change c = object.change();
    return c != null && c.getDest().getParentKey().get().startsWith(getValue());
  }

  @Override
  public int getCost() {
    return 1;
  }
}
