// Copyright (C) 2011 The Android Open Source Project
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

package com.google.gerrit.reviewdb.client;

import com.google.gwtorm.client.CompoundKey;
import java.util.Objects;

/** Membership of an {@link AccountGroup} in an {@link AccountGroup}. */
public final class AccountGroupById {
  public static Key key(AccountGroup.Id groupId, AccountGroup.UUID includeUuid) {
    return new Key(groupId, includeUuid);
  }

  public static class Key extends CompoundKey<AccountGroup.Id> {
    private static final long serialVersionUID = 1L;

    protected AccountGroup.Id groupId;

    protected AccountGroup.UUID includeUUID;

    protected Key() {
      groupId = new AccountGroup.Id();
      includeUUID = new AccountGroup.UUID();
    }

    public Key(AccountGroup.Id g, AccountGroup.UUID u) {
      groupId = g;
      includeUUID = u;
    }

    @Override
    public AccountGroup.Id getParentKey() {
      return groupId;
    }

    public AccountGroup.Id getGroupId() {
      return groupId;
    }

    public AccountGroup.Id groupId() {
      return getParentKey();
    }

    public AccountGroup.UUID getIncludeUUID() {
      return includeUUID;
    }

    public AccountGroup.UUID includeUuid() {
      return getIncludeUUID();
    }

    @Override
    public com.google.gwtorm.client.Key<?>[] members() {
      return new com.google.gwtorm.client.Key<?>[] {includeUUID};
    }
  }

  protected Key key;

  protected AccountGroupById() {}

  public AccountGroupById(AccountGroupById.Key k) {
    key = k;
  }

  public AccountGroupById.Key getKey() {
    return key;
  }

  public AccountGroup.Id getGroupId() {
    return key.groupId;
  }

  public AccountGroup.UUID getIncludeUUID() {
    return key.includeUUID;
  }

  @Override
  public boolean equals(Object o) {
    return (o instanceof AccountGroupById) && Objects.equals(key, ((AccountGroupById) o).key);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{key=" + key + "}";
  }
}
