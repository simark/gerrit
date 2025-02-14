// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.acceptance.api.change;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.gerrit.acceptance.GitUtil.assertPushOk;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.PushOneCommit.FILE_CONTENT;
import static com.google.gerrit.acceptance.PushOneCommit.FILE_NAME;
import static com.google.gerrit.acceptance.PushOneCommit.SUBJECT;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CHANGE_ACTIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CHECK;
import static com.google.gerrit.extensions.client.ListChangesOption.COMMIT_FOOTERS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_ACTIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_ACCOUNTS;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS;
import static com.google.gerrit.extensions.client.ListChangesOption.LABELS;
import static com.google.gerrit.extensions.client.ListChangesOption.MESSAGES;
import static com.google.gerrit.extensions.client.ListChangesOption.PUSH_CERTIFICATES;
import static com.google.gerrit.extensions.client.ListChangesOption.REVIEWED;
import static com.google.gerrit.extensions.client.ListChangesOption.TRACKING_IDS;
import static com.google.gerrit.extensions.client.ReviewerState.CC;
import static com.google.gerrit.extensions.client.ReviewerState.REMOVED;
import static com.google.gerrit.extensions.client.ReviewerState.REVIEWER;
import static com.google.gerrit.reviewdb.client.RefNames.changeMetaRef;
import static com.google.gerrit.server.StarredChangesUtil.DEFAULT_LABEL;
import static com.google.gerrit.server.group.SystemGroupBackend.ANONYMOUS_USERS;
import static com.google.gerrit.server.group.SystemGroupBackend.CHANGE_OWNER;
import static com.google.gerrit.server.group.SystemGroupBackend.PROJECT_OWNERS;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.project.testing.Util.category;
import static com.google.gerrit.server.project.testing.Util.value;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.truth.ThrowableSubject;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ChangeIndexedCounter;
import com.google.gerrit.acceptance.GerritConfig;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.group.GroupOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.common.FooterConstants;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.common.data.LabelFunction;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.api.accounts.DeleteDraftCommentsInput;
import com.google.gerrit.extensions.api.changes.AddReviewerInput;
import com.google.gerrit.extensions.api.changes.AddReviewerResult;
import com.google.gerrit.extensions.api.changes.DeleteReviewerInput;
import com.google.gerrit.extensions.api.changes.DeleteVoteInput;
import com.google.gerrit.extensions.api.changes.DraftApi;
import com.google.gerrit.extensions.api.changes.DraftInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.NotifyInfo;
import com.google.gerrit.extensions.api.changes.RebaseInput;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.extensions.api.changes.RelatedChangeAndCommitInfo;
import com.google.gerrit.extensions.api.changes.RevertInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.DraftHandling;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.api.changes.ReviewerInfo;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.changes.StarsInput;
import com.google.gerrit.extensions.api.groups.GroupApi;
import com.google.gerrit.extensions.api.projects.BranchApi;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.api.projects.ConfigInput;
import com.google.gerrit.extensions.api.projects.ProjectApi;
import com.google.gerrit.extensions.api.projects.ProjectInput;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.Comment.Range;
import com.google.gerrit.extensions.client.InheritableBoolean;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.GitPerson;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.common.MergePatchSetInput;
import com.google.gerrit.extensions.common.PureRevertInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.common.TrackingIdInfo;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.index.query.PostFilterPredicate;
import com.google.gerrit.mail.Address;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.StarredChangesUtil;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.git.ChangeMessageModifier;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.index.change.ChangeIndex;
import com.google.gerrit.server.index.change.ChangeIndexCollection;
import com.google.gerrit.server.index.change.IndexedChangeQuery;
import com.google.gerrit.server.project.testing.Util;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeQueryBuilder.ChangeOperatorFactory;
import com.google.gerrit.server.restapi.change.PostReview;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.testing.FakeEmailSender.Message;
import com.google.gerrit.testing.TestTimeUtil;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@NoHttpd
public class ChangeIT extends AbstractDaemonTest {
  private String systemTimeZone;

  @Inject private AccountOperations accountOperations;
  @Inject private ChangeIndexCollection changeIndexCollection;
  @Inject private DynamicSet<ChangeIndexedListener> changeIndexedListeners;
  @Inject private DynamicSet<ChangeMessageModifier> changeMessageModifiers;
  @Inject private GroupOperations groupOperations;
  @Inject private IndexConfig indexConfig;
  @Inject private ProjectOperations projectOperations;
  @Inject private RequestScopeOperations requestScopeOperations;

  private ChangeIndexedCounter changeIndexedCounter;
  private RegistrationHandle changeIndexedCounterHandle;

  @Before
  public void setTimeForTesting() {
    systemTimeZone = System.setProperty("user.timezone", "US/Eastern");
  }

  @After
  public void resetTime() {
    TestTimeUtil.useSystemTime();
    System.setProperty("user.timezone", systemTimeZone);
  }

  @Before
  public void addChangeIndexedCounter() {
    changeIndexedCounter = new ChangeIndexedCounter();
    changeIndexedCounterHandle = changeIndexedListeners.add("gerrit", changeIndexedCounter);
  }

  @After
  public void removeChangeIndexedCounter() {
    if (changeIndexedCounterHandle != null) {
      changeIndexedCounterHandle.remove();
    }
  }

  @Test
  public void get() throws Exception {
    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();
    ChangeInfo c = info(triplet);
    assertThat(c.id).isEqualTo(triplet);
    assertThat(c.project).isEqualTo(project.get());
    assertThat(c.branch).isEqualTo("master");
    assertThat(c.status).isEqualTo(ChangeStatus.NEW);
    assertThat(c.subject).isEqualTo("test commit");
    assertThat(c.submitType).isEqualTo(SubmitType.MERGE_IF_NECESSARY);
    assertThat(c.mergeable).isTrue();
    assertThat(c.changeId).isEqualTo(r.getChangeId());
    assertThat(c.created).isEqualTo(c.updated);
    assertThat(c._number).isEqualTo(r.getChange().getId().get());

    assertThat(c.owner._accountId).isEqualTo(admin.id().get());
    assertThat(c.owner.name).isNull();
    assertThat(c.owner.email).isNull();
    assertThat(c.owner.username).isNull();
    assertThat(c.owner.avatars).isNull();
  }

  @Test
  public void skipMergeable() throws Exception {
    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();
    ChangeInfo c =
        gApi.changes().id(triplet).get(ImmutableList.of(ListChangesOption.SKIP_MERGEABLE));
    assertThat(c.mergeable).isNull();

    c = gApi.changes().id(triplet).get();
    assertThat(c.mergeable).isTrue();
  }

  @Test
  @GerritConfig(name = "change.api.excludeMergeableInChangeInfo", value = "true")
  public void excludeMergeableInChangeInfo() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    assertThat(c.mergeable).isNull();
  }

  @Test
  public void setWorkInProgressNotAllowedWithoutPermission() throws Exception {
    PushOneCommit.Result rwip = createChange();
    String changeId = rwip.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("toggle work in progress state not permitted");
    gApi.changes().id(changeId).setWorkInProgress();
  }

  @Test
  public void setWorkInProgressAllowedAsAdmin() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    String changeId =
        gApi.changes().create(new ChangeInput(project.get(), "master", "Test Change")).get().id;

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).setWorkInProgress();
    assertThat(gApi.changes().id(changeId).get().workInProgress).isTrue();
  }

  @Test
  public void setWorkInProgressAllowedAsProjectOwner() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    String changeId =
        gApi.changes().create(new ChangeInput(project.get(), "master", "Test Change")).get().id;

    com.google.gerrit.acceptance.TestAccount user2 = accountCreator.user2();
    grant(project, "refs/*", Permission.OWNER, false, REGISTERED_USERS);
    requestScopeOperations.setApiUser(user2.id());
    gApi.changes().id(changeId).setWorkInProgress();
    assertThat(gApi.changes().id(changeId).get().workInProgress).isTrue();
  }

  @Test
  public void createWipChangeWithWorkInProgressByDefaultForProject() throws Exception {
    ConfigInput input = new ConfigInput();
    input.workInProgressByDefault = InheritableBoolean.TRUE;
    gApi.projects().name(project.get()).config(input);
    String changeId =
        gApi.changes().create(new ChangeInput(project.get(), "master", "Test Change")).get().id;
    assertThat(gApi.changes().id(changeId).get().workInProgress).isTrue();
  }

  @Test
  public void setReadyForReviewNotAllowedWithoutPermission() throws Exception {
    PushOneCommit.Result rready = createChange();
    String changeId = rready.getChangeId();
    gApi.changes().id(changeId).setWorkInProgress();

    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("toggle work in progress state not permitted");
    gApi.changes().id(changeId).setReadyForReview();
  }

  @Test
  public void setReadyForReviewAllowedAsAdmin() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    String changeId =
        gApi.changes().create(new ChangeInput(project.get(), "master", "Test Change")).get().id;
    gApi.changes().id(changeId).setWorkInProgress();

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).setReadyForReview();
    assertThat(gApi.changes().id(changeId).get().workInProgress).isNull();
  }

  @Test
  public void setReadyForReviewAllowedAsProjectOwner() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    String changeId =
        gApi.changes().create(new ChangeInput(project.get(), "master", "Test Change")).get().id;
    gApi.changes().id(changeId).setWorkInProgress();

    com.google.gerrit.acceptance.TestAccount user2 = accountCreator.user2();
    grant(project, "refs/*", Permission.OWNER, false, REGISTERED_USERS);
    requestScopeOperations.setApiUser(user2.id());
    gApi.changes().id(changeId).setReadyForReview();
    assertThat(gApi.changes().id(changeId).get().workInProgress).isNull();
  }

  @Test
  public void hasReviewStarted() throws Exception {
    PushOneCommit.Result r = createWorkInProgressChange();
    String changeId = r.getChangeId();
    ChangeInfo info = gApi.changes().id(changeId).get();
    assertThat(info.hasReviewStarted).isFalse();

    gApi.changes().id(changeId).setReadyForReview();
    info = gApi.changes().id(changeId).get();
    assertThat(info.hasReviewStarted).isTrue();
  }

  @Test
  public void pendingReviewersInNoteDb() throws Exception {
    ConfigInput conf = new ConfigInput();
    conf.enableReviewerByEmail = InheritableBoolean.TRUE;
    gApi.projects().name(project.get()).config(conf);

    PushOneCommit.Result r = createWorkInProgressChange();
    String changeId = r.getChangeId();
    assertThat(gApi.changes().id(changeId).get().pendingReviewers).isEmpty();

    // Add some pending reviewers.
    String email1 = name("user1") + "@example.com";
    String email2 = name("user2") + "@example.com";
    String email3 = name("user3") + "@example.com";
    String email4 = name("user4") + "@example.com";
    accountOperations
        .newAccount()
        .username(name("user1"))
        .preferredEmail(email1)
        .fullname("User 1")
        .create();
    accountOperations
        .newAccount()
        .username(name("user2"))
        .preferredEmail(email2)
        .fullname("User 2")
        .create();
    accountOperations
        .newAccount()
        .username(name("user3"))
        .preferredEmail(email3)
        .fullname("User 3")
        .create();
    accountOperations
        .newAccount()
        .username(name("user4"))
        .preferredEmail(email4)
        .fullname("User 4")
        .create();
    ReviewInput in =
        ReviewInput.noScore()
            .reviewer(email1)
            .reviewer(email2)
            .reviewer(email3, CC, false)
            .reviewer(email4, CC, false)
            .reviewer("byemail1@example.com")
            .reviewer("byemail2@example.com")
            .reviewer("byemail3@example.com", CC, false)
            .reviewer("byemail4@example.com", CC, false);
    ReviewResult result = gApi.changes().id(changeId).revision("current").review(in);
    assertThat(result.reviewers).isNotEmpty();
    ChangeInfo info = gApi.changes().id(changeId).get();
    Function<Collection<AccountInfo>, Collection<String>> toEmails =
        ais -> ais.stream().map(ai -> ai.email).collect(toSet());
    assertThat(toEmails.apply(info.pendingReviewers.get(REVIEWER)))
        .containsExactly(
            admin.email(), email1, email2, "byemail1@example.com", "byemail2@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(CC)))
        .containsExactly(email3, email4, "byemail3@example.com", "byemail4@example.com");
    assertThat(info.pendingReviewers.get(REMOVED)).isNull();

    // Stage some pending reviewer removals.
    gApi.changes().id(changeId).reviewer(email1).remove();
    gApi.changes().id(changeId).reviewer(email3).remove();
    gApi.changes().id(changeId).reviewer("byemail1@example.com").remove();
    gApi.changes().id(changeId).reviewer("byemail3@example.com").remove();
    info = gApi.changes().id(changeId).get();
    assertThat(toEmails.apply(info.pendingReviewers.get(REVIEWER)))
        .containsExactly(admin.email(), email2, "byemail2@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(CC)))
        .containsExactly(email4, "byemail4@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(REMOVED)))
        .containsExactly(email1, email3, "byemail1@example.com", "byemail3@example.com");

    // "Undo" a removal.
    in = ReviewInput.noScore().reviewer(email1);
    gApi.changes().id(changeId).revision("current").review(in);
    info = gApi.changes().id(changeId).get();
    assertThat(toEmails.apply(info.pendingReviewers.get(REVIEWER)))
        .containsExactly(admin.email(), email1, email2, "byemail2@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(CC)))
        .containsExactly(email4, "byemail4@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(REMOVED)))
        .containsExactly(email3, "byemail1@example.com", "byemail3@example.com");

    // "Commit" by moving out of WIP.
    gApi.changes().id(changeId).setReadyForReview();
    info = gApi.changes().id(changeId).get();
    assertThat(info.pendingReviewers).isEmpty();
    assertThat(toEmails.apply(info.reviewers.get(REVIEWER)))
        .containsExactly(admin.email(), email1, email2, "byemail2@example.com");
    assertThat(toEmails.apply(info.reviewers.get(CC)))
        .containsExactly(email4, "byemail4@example.com");
    assertThat(info.reviewers.get(REMOVED)).isNull();
  }

  @Test
  public void toggleWorkInProgressState() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    // With message
    gApi.changes().id(changeId).setWorkInProgress("Needs some refactoring");

    ChangeInfo info = gApi.changes().id(changeId).get();

    assertThat(info.workInProgress).isTrue();
    assertThat(Iterables.getLast(info.messages).message).contains("Needs some refactoring");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_WIP);

    gApi.changes().id(changeId).setReadyForReview("PTAL");

    info = gApi.changes().id(changeId).get();
    assertThat(info.workInProgress).isNull();
    assertThat(Iterables.getLast(info.messages).message).contains("PTAL");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_READY);

    // No message
    gApi.changes().id(changeId).setWorkInProgress();

    info = gApi.changes().id(changeId).get();

    assertThat(info.workInProgress).isTrue();
    assertThat(Iterables.getLast(info.messages).message).isEqualTo("Set Work In Progress");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_WIP);

    gApi.changes().id(changeId).setReadyForReview();

    info = gApi.changes().id(changeId).get();
    assertThat(info.workInProgress).isNull();
    assertThat(Iterables.getLast(info.messages).message).isEqualTo("Set Ready For Review");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_READY);
  }

  @Test
  public void toggleWorkInProgressStateByNonOwnerWithPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    String refactor = "Needs some refactoring";
    String ptal = "PTAL";

    grant(
        project,
        "refs/heads/master",
        Permission.TOGGLE_WORK_IN_PROGRESS_STATE,
        false,
        REGISTERED_USERS);

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).setWorkInProgress(refactor);

    ChangeInfo info = gApi.changes().id(changeId).get();

    assertThat(info.workInProgress).isTrue();
    assertThat(Iterables.getLast(info.messages).message).contains(refactor);
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_WIP);

    gApi.changes().id(changeId).setReadyForReview(ptal);

    info = gApi.changes().id(changeId).get();
    assertThat(info.workInProgress).isNull();
    assertThat(Iterables.getLast(info.messages).message).contains(ptal);
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_READY);
  }

  @Test
  public void reviewAndStartReview() throws Exception {
    PushOneCommit.Result r = createWorkInProgressChange();
    r.assertOkStatus();
    assertThat(r.getChange().change().isWorkInProgress()).isTrue();

    ReviewInput in = ReviewInput.noScore().setWorkInProgress(false);
    ReviewResult result = gApi.changes().id(r.getChangeId()).revision("current").review(in);
    assertThat(result.ready).isTrue();

    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.workInProgress).isNull();
  }

  @Test
  public void reviewAndMoveToWorkInProgress() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(r.getChange().change().isWorkInProgress()).isFalse();

    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    ReviewResult result = gApi.changes().id(r.getChangeId()).revision("current").review(in);
    assertThat(result.ready).isNull();

    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  public void reviewAndSetWorkInProgressAndAddReviewerAndVote() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(r.getChange().change().isWorkInProgress()).isFalse();

    ReviewInput in =
        ReviewInput.approve()
            .reviewer(user.email())
            .label("Code-Review", 1)
            .setWorkInProgress(true);
    gApi.changes().id(r.getChangeId()).revision("current").review(in);

    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.workInProgress).isTrue();
    assertThat(info.reviewers.get(REVIEWER).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(admin.id().get(), user.id().get());
    assertThat(info.labels.get("Code-Review").recommended._accountId).isEqualTo(admin.id().get());
  }

  @Test
  public void reviewWithWorkInProgressAndReadyReturnsError() throws Exception {
    PushOneCommit.Result r = createChange();
    ReviewInput in = ReviewInput.noScore();
    in.ready = true;
    in.workInProgress = true;
    ReviewResult result = gApi.changes().id(r.getChangeId()).revision("current").review(in);
    assertThat(result.error).isEqualTo(PostReview.ERROR_WIP_READY_MUTUALLY_EXCLUSIVE);
  }

  @Test
  @TestProjectInput(cloneAs = "user")
  public void reviewWithWorkInProgressChangeOwner() throws Exception {
    PushOneCommit push = pushFactory.create(user.newIdent(), testRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();
    assertThat(r.getChange().change().getOwner()).isEqualTo(user.id());

    requestScopeOperations.setApiUser(user.id());
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    gApi.changes().id(r.getChangeId()).current().review(in);
    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  @TestProjectInput(cloneAs = "user")
  public void reviewWithWithWorkInProgressAdmin() throws Exception {
    PushOneCommit push = pushFactory.create(user.newIdent(), testRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();
    assertThat(r.getChange().change().getOwner()).isEqualTo(user.id());

    requestScopeOperations.setApiUser(admin.id());
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    gApi.changes().id(r.getChangeId()).current().review(in);
    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  public void reviewWithWorkInProgressByNonOwnerReturnsError() throws Exception {
    PushOneCommit.Result r = createChange();
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("toggle work in progress state not permitted");
    gApi.changes().id(r.getChangeId()).current().review(in);
  }

  @Test
  public void reviewWithWorkInProgressByNonOwnerWithPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    grant(
        project,
        "refs/heads/master",
        Permission.TOGGLE_WORK_IN_PROGRESS_STATE,
        false,
        REGISTERED_USERS);
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).current().review(in);
    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  public void reviewWithReadyByNonOwnerReturnsError() throws Exception {
    PushOneCommit.Result r = createChange();
    ReviewInput in = ReviewInput.noScore().setReady(true);
    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("toggle work in progress state not permitted");
    gApi.changes().id(r.getChangeId()).current().review(in);
  }

  @Test
  public void getAmbiguous() throws Exception {
    PushOneCommit.Result r1 = createChange();
    String changeId = r1.getChangeId();
    gApi.changes().id(changeId).get();

    BranchInput b = new BranchInput();
    b.revision = repo().exactRef("HEAD").getObjectId().name();
    gApi.projects().name(project.get()).branch("other").create(b);

    PushOneCommit push2 =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            PushOneCommit.FILE_NAME,
            PushOneCommit.FILE_CONTENT,
            changeId);
    PushOneCommit.Result r2 = push2.to("refs/for/other");
    assertThat(r2.getChangeId()).isEqualTo(changeId);

    exception.expect(ResourceNotFoundException.class);
    exception.expectMessage("Multiple changes found for " + changeId);
    gApi.changes().id(changeId).get();
  }

  @Test
  public void revert() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();
    ChangeInfo revertChange = gApi.changes().id(r.getChangeId()).revert().get();

    // expected messages on source change:
    // 1. Uploaded patch set 1.
    // 2. Patch Set 1: Code-Review+2
    // 3. Change has been successfully merged by Administrator
    // 4. Patch Set 1: Reverted
    List<ChangeMessageInfo> sourceMessages =
        new ArrayList<>(gApi.changes().id(r.getChangeId()).get().messages);
    assertThat(sourceMessages).hasSize(4);
    String expectedMessage =
        String.format("Created a revert of this change as %s", revertChange.changeId);
    assertThat(sourceMessages.get(3).message).isEqualTo(expectedMessage);

    assertThat(revertChange.messages).hasSize(1);
    assertThat(revertChange.messages.iterator().next().message).isEqualTo("Uploaded patch set 1.");
    assertThat(revertChange.revertOf).isEqualTo(gApi.changes().id(r.getChangeId()).get()._number);
  }

  @Test
  public void revertNotifications() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).addReviewer(user.email());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    sender.clear();
    ChangeInfo revertChange = gApi.changes().id(r.getChangeId()).revert().get();

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(2);
    assertThat(sender.getMessages(revertChange.changeId, "newchange")).hasSize(1);
    assertThat(sender.getMessages(r.getChangeId(), "revert")).hasSize(1);
  }

  @Test
  public void suppressRevertNotifications() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).addReviewer(user.email());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    RevertInput revertInput = new RevertInput();
    revertInput.notify = NotifyHandling.NONE;

    sender.clear();
    gApi.changes().id(r.getChangeId()).revert(revertInput).get();
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void revertPreservesReviewersAndCcs() throws Exception {
    PushOneCommit.Result r = createChange();

    ReviewInput in = ReviewInput.approve();
    in.reviewer(user.email());
    in.reviewer(accountCreator.user2().email(), ReviewerState.CC, true);
    // Add user as reviewer that will create the revert
    in.reviewer(accountCreator.admin2().email());

    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(in);
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    // expect both the original reviewers and CCs to be preserved
    // original owner should be added as reviewer, user requesting the revert (new owner) removed
    requestScopeOperations.setApiUser(accountCreator.admin2().id());
    Map<ReviewerState, Collection<AccountInfo>> result =
        gApi.changes().id(r.getChangeId()).revert().get().reviewers;
    assertThat(result).containsKey(ReviewerState.REVIEWER);

    List<Integer> reviewers =
        result.get(ReviewerState.REVIEWER).stream().map(a -> a._accountId).collect(toList());
    assertThat(result).containsKey(ReviewerState.CC);
    List<Integer> ccs =
        result.get(ReviewerState.CC).stream().map(a -> a._accountId).collect(toList());
    assertThat(ccs).containsExactly(accountCreator.user2().id().get());
    assertThat(reviewers).containsExactly(user.id().get(), admin.id().get());
  }

  @Test
  @TestProjectInput(createEmptyCommit = false)
  public void revertInitialCommit() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    exception.expect(ResourceConflictException.class);
    exception.expectMessage("Cannot revert initial commit");
    gApi.changes().id(r.getChangeId()).revert();
  }

  @FunctionalInterface
  private interface Rebase {
    void call(String id) throws RestApiException;
  }

  @Test
  public void rebaseViaRevisionApi() throws Exception {
    testRebase(id -> gApi.changes().id(id).current().rebase());
  }

  @Test
  public void rebaseViaChangeApi() throws Exception {
    testRebase(id -> gApi.changes().id(id).rebase());
  }

  private void testRebase(Rebase rebase) throws Exception {
    // Create two changes both with the same parent
    PushOneCommit.Result r = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    // Approve and submit the first change
    RevisionApi revision = gApi.changes().id(r.getChangeId()).current();
    revision.review(ReviewInput.approve());
    revision.submit();

    // Add an approval whose score should be copied on trivial rebase
    gApi.changes().id(r2.getChangeId()).current().review(ReviewInput.recommend());

    String changeId = r2.getChangeId();
    // Rebase the second change
    rebase.call(changeId);

    // Second change should have 2 patch sets and an approval
    ChangeInfo c2 = gApi.changes().id(changeId).get(CURRENT_REVISION, DETAILED_LABELS);
    assertThat(c2.revisions.get(c2.currentRevision)._number).isEqualTo(2);

    // ...and the committer and description should be correct
    ChangeInfo info = gApi.changes().id(changeId).get(CURRENT_REVISION, CURRENT_COMMIT);
    GitPerson committer = info.revisions.get(info.currentRevision).commit.committer;
    assertThat(committer.name).isEqualTo(admin.fullName());
    assertThat(committer.email).isEqualTo(admin.email());
    String description = info.revisions.get(info.currentRevision).description;
    assertThat(description).isEqualTo("Rebase");

    // ...and the approval was copied
    LabelInfo cr = c2.labels.get("Code-Review");
    assertThat(cr).isNotNull();
    assertThat(cr.all).hasSize(1);
    assertThat(cr.all.get(0).value).isEqualTo(1);

    // Rebasing the second change again should fail
    exception.expect(ResourceConflictException.class);
    exception.expectMessage("Change is already up to date");
    gApi.changes().id(changeId).current().rebase();
  }

  @Test
  public void rebaseOnNonExistingChange() throws Exception {
    String changeId = createChange().getChangeId();
    RebaseInput in = new RebaseInput();
    in.base = "999999";
    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage("Base change not found: " + in.base);
    gApi.changes().id(changeId).rebase(in);
  }

  @Test
  public void rebaseOnChangeNumber() throws Exception {
    String branchTip = testRepo.getRepository().exactRef("HEAD").getObjectId().name();
    PushOneCommit.Result r1 = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    ChangeInfo ci2 = get(r2.getChangeId(), CURRENT_REVISION, CURRENT_COMMIT);
    RevisionInfo ri2 = ci2.revisions.get(ci2.currentRevision);
    assertThat(ri2.commit.parents.get(0).commit).isEqualTo(branchTip);

    Change.Id id1 = r1.getChange().getId();
    RebaseInput in = new RebaseInput();
    in.base = id1.toString();
    gApi.changes().id(r2.getChangeId()).rebase(in);

    Change.Id id2 = r2.getChange().getId();
    ci2 = get(r2.getChangeId(), CURRENT_REVISION, CURRENT_COMMIT);
    ri2 = ci2.revisions.get(ci2.currentRevision);
    assertThat(ri2.commit.parents.get(0).commit).isEqualTo(r1.getCommit().name());

    List<RelatedChangeAndCommitInfo> related =
        gApi.changes().id(id2.get()).revision(ri2._number).related().changes;
    assertThat(related).hasSize(2);
    assertThat(related.get(0)._changeNumber).isEqualTo(id2.get());
    assertThat(related.get(0)._revisionNumber).isEqualTo(2);
    assertThat(related.get(1)._changeNumber).isEqualTo(id1.get());
    assertThat(related.get(1)._revisionNumber).isEqualTo(1);
  }

  @Test
  public void rebaseOnClosedChange() throws Exception {
    String branchTip = testRepo.getRepository().exactRef("HEAD").getObjectId().name();
    PushOneCommit.Result r1 = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    ChangeInfo ci2 = get(r2.getChangeId(), CURRENT_REVISION, CURRENT_COMMIT);
    RevisionInfo ri2 = ci2.revisions.get(ci2.currentRevision);
    assertThat(ri2.commit.parents.get(0).commit).isEqualTo(branchTip);

    // Submit first change.
    Change.Id id1 = r1.getChange().getId();
    gApi.changes().id(id1.get()).current().review(ReviewInput.approve());
    gApi.changes().id(id1.get()).current().submit();

    // Rebase second change on first change.
    RebaseInput in = new RebaseInput();
    in.base = id1.toString();
    gApi.changes().id(r2.getChangeId()).rebase(in);

    Change.Id id2 = r2.getChange().getId();
    ci2 = get(r2.getChangeId(), CURRENT_REVISION, CURRENT_COMMIT);
    ri2 = ci2.revisions.get(ci2.currentRevision);
    assertThat(ri2.commit.parents.get(0).commit).isEqualTo(r1.getCommit().name());

    assertThat(gApi.changes().id(id2.get()).revision(ri2._number).related().changes).isEmpty();
  }

  @Test
  public void rebaseFromRelationChainToClosedChange() throws Exception {
    PushOneCommit.Result r1 = createChange();
    testRepo.reset("HEAD~1");

    createChange();
    PushOneCommit.Result r3 = createChange();

    // Submit first change.
    Change.Id id1 = r1.getChange().getId();
    gApi.changes().id(id1.get()).current().review(ReviewInput.approve());
    gApi.changes().id(id1.get()).current().submit();

    // Rebase third change on first change.
    RebaseInput in = new RebaseInput();
    in.base = id1.toString();
    gApi.changes().id(r3.getChangeId()).rebase(in);

    Change.Id id3 = r3.getChange().getId();
    ChangeInfo ci3 = get(r3.getChangeId(), CURRENT_REVISION, CURRENT_COMMIT);
    RevisionInfo ri3 = ci3.revisions.get(ci3.currentRevision);
    assertThat(ri3.commit.parents.get(0).commit).isEqualTo(r1.getCommit().name());

    assertThat(gApi.changes().id(id3.get()).revision(ri3._number).related().changes).isEmpty();
  }

  @Test
  public void rebaseNotAllowedWithoutPermission() throws Exception {
    // Create two changes both with the same parent
    PushOneCommit.Result r = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    // Approve and submit the first change
    RevisionApi revision = gApi.changes().id(r.getChangeId()).current();
    revision.review(ReviewInput.approve());
    revision.submit();

    // Rebase the second
    String changeId = r2.getChangeId();
    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("rebase not permitted");
    gApi.changes().id(changeId).rebase();
  }

  @Test
  public void rebaseAllowedWithPermission() throws Exception {
    // Create two changes both with the same parent
    PushOneCommit.Result r = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    // Approve and submit the first change
    RevisionApi revision = gApi.changes().id(r.getChangeId()).current();
    revision.review(ReviewInput.approve());
    revision.submit();

    grant(project, "refs/heads/master", Permission.REBASE, false, REGISTERED_USERS);

    // Rebase the second
    String changeId = r2.getChangeId();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).rebase();
  }

  @Test
  public void rebaseNotAllowedWithoutPushPermission() throws Exception {
    // Create two changes both with the same parent
    PushOneCommit.Result r = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    // Approve and submit the first change
    RevisionApi revision = gApi.changes().id(r.getChangeId()).current();
    revision.review(ReviewInput.approve());
    revision.submit();

    grant(project, "refs/heads/master", Permission.REBASE, false, REGISTERED_USERS);
    block("refs/for/*", Permission.PUSH, REGISTERED_USERS);

    // Rebase the second
    String changeId = r2.getChangeId();
    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("rebase not permitted");
    gApi.changes().id(changeId).rebase();
  }

  @Test
  public void rebaseNotAllowedForOwnerWithoutPushPermission() throws Exception {
    // Create two changes both with the same parent
    PushOneCommit.Result r = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    // Approve and submit the first change
    RevisionApi revision = gApi.changes().id(r.getChangeId()).current();
    revision.review(ReviewInput.approve());
    revision.submit();

    block("refs/for/*", Permission.PUSH, REGISTERED_USERS);

    // Rebase the second
    String changeId = r2.getChangeId();
    exception.expect(AuthException.class);
    exception.expectMessage("rebase not permitted");
    gApi.changes().id(changeId).rebase();
  }

  @Test
  public void deleteNewChangeAsAdmin() throws Exception {
    deleteChangeAsUser(admin, admin);
  }

  @Test
  @TestProjectInput(cloneAs = "user")
  public void deleteNewChangeAsNormalUser() throws Exception {
    PushOneCommit.Result changeResult =
        pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
    String changeId = changeResult.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("delete not permitted");
    gApi.changes().id(changeId).delete();
  }

  @Test
  public void deleteNewChangeAsUserWithDeleteChangesPermissionForGroup() throws Exception {
    allow("refs/*", Permission.DELETE_CHANGES, REGISTERED_USERS);
    deleteChangeAsUser(admin, user);
  }

  @Test
  public void deleteNewChangeAsUserWithDeleteChangesPermissionForProjectOwners() throws Exception {
    GroupApi groupApi = gApi.groups().create(name("delete-change"));
    groupApi.addMembers("user");

    ProjectInput in = new ProjectInput();
    in.name = name("delete-change");
    in.owners = Lists.newArrayListWithCapacity(1);
    in.owners.add(groupApi.name());
    in.createEmptyCommit = true;
    ProjectApi api = gApi.projects().create(in);

    Project.NameKey nameKey = new Project.NameKey(api.get().name);

    try (ProjectConfigUpdate u = updateProject(nameKey)) {
      Util.allow(u.getConfig(), Permission.DELETE_CHANGES, PROJECT_OWNERS, "refs/*");
      u.save();
    }

    deleteChangeAsUser(nameKey, admin, user);
  }

  @Test
  public void deleteChangeAsUserWithDeleteOwnChangesPermissionForGroup() throws Exception {
    allow("refs/*", Permission.DELETE_OWN_CHANGES, REGISTERED_USERS);
    deleteChangeAsUser(user, user);
  }

  @Test
  public void deleteChangeAsUserWithDeleteOwnChangesPermissionForOwners() throws Exception {
    allow("refs/*", Permission.DELETE_OWN_CHANGES, CHANGE_OWNER);
    deleteChangeAsUser(user, user);
  }

  private void deleteChangeAsUser(
      com.google.gerrit.acceptance.TestAccount owner,
      com.google.gerrit.acceptance.TestAccount deleteAs)
      throws Exception {
    deleteChangeAsUser(project, owner, deleteAs);
  }

  private void deleteChangeAsUser(
      Project.NameKey projectName,
      com.google.gerrit.acceptance.TestAccount owner,
      com.google.gerrit.acceptance.TestAccount deleteAs)
      throws Exception {
    try {
      requestScopeOperations.setApiUser(owner.id());
      ChangeInput in = new ChangeInput();
      in.project = projectName.get();
      in.branch = "refs/heads/master";
      in.subject = "test";
      ChangeInfo changeInfo = gApi.changes().create(in).get();
      String changeId = changeInfo.changeId;
      int id = changeInfo._number;
      String commit = changeInfo.currentRevision;

      assertThat(gApi.changes().id(changeId).info().owner._accountId).isEqualTo(owner.id().get());

      requestScopeOperations.setApiUser(deleteAs.id());
      gApi.changes().id(changeId).delete();

      assertThat(query(changeId)).isEmpty();

      String ref = new Change.Id(id).toRefPrefix() + "1";
      eventRecorder.assertRefUpdatedEvents(projectName.get(), ref, null, commit, commit, null);
      eventRecorder.assertChangeDeletedEvents(changeId, deleteAs.email());
    } finally {
      removePermission(project, "refs/*", Permission.DELETE_OWN_CHANGES);
      removePermission(project, "refs/*", Permission.DELETE_CHANGES);
    }
  }

  @Test
  public void deleteNewChangeOfAnotherUserAsAdmin() throws Exception {
    deleteChangeAsUser(user, admin);
  }

  @Test
  public void deleteNewChangeOfAnotherUserWithDeleteOwnChangesPermission() throws Exception {
    allow("refs/*", Permission.DELETE_OWN_CHANGES, REGISTERED_USERS);

    try {
      PushOneCommit.Result changeResult = createChange();
      String changeId = changeResult.getChangeId();

      requestScopeOperations.setApiUser(user.id());
      exception.expect(AuthException.class);
      exception.expectMessage("delete not permitted");
      gApi.changes().id(changeId).delete();
    } finally {
      removePermission(project, "refs/*", Permission.DELETE_OWN_CHANGES);
    }
  }

  @Test
  @TestProjectInput(createEmptyCommit = false)
  public void deleteNewChangeForBranchWithoutCommits() throws Exception {
    PushOneCommit.Result changeResult = createChange();
    String changeId = changeResult.getChangeId();

    gApi.changes().id(changeId).delete();

    assertThat(query(changeId)).isEmpty();
  }

  @Test
  @TestProjectInput(cloneAs = "user")
  public void deleteAbandonedChangeAsNormalUser() throws Exception {
    PushOneCommit.Result changeResult =
        pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
    String changeId = changeResult.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).abandon();

    exception.expect(AuthException.class);
    exception.expectMessage("delete not permitted");
    gApi.changes().id(changeId).delete();
  }

  @Test
  @TestProjectInput(cloneAs = "user")
  public void deleteAbandonedChangeOfAnotherUserAsAdmin() throws Exception {
    PushOneCommit.Result changeResult =
        pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
    String changeId = changeResult.getChangeId();

    gApi.changes().id(changeId).abandon();

    gApi.changes().id(changeId).delete();

    assertThat(query(changeId)).isEmpty();
  }

  @Test
  public void deleteMergedChange() throws Exception {
    PushOneCommit.Result changeResult = createChange();
    String changeId = changeResult.getChangeId();

    merge(changeResult);

    exception.expect(MethodNotAllowedException.class);
    exception.expectMessage("delete not permitted");
    gApi.changes().id(changeId).delete();
  }

  @Test
  @TestProjectInput(cloneAs = "user")
  public void deleteMergedChangeWithDeleteOwnChangesPermission() throws Exception {
    allow("refs/*", Permission.DELETE_OWN_CHANGES, REGISTERED_USERS);

    try {
      PushOneCommit.Result changeResult =
          pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
      String changeId = changeResult.getChangeId();

      merge(changeResult);

      requestScopeOperations.setApiUser(user.id());
      exception.expect(MethodNotAllowedException.class);
      exception.expectMessage("delete not permitted");
      gApi.changes().id(changeId).delete();
    } finally {
      removePermission(project, "refs/*", Permission.DELETE_OWN_CHANGES);
    }
  }

  @Test
  public void deleteNewChangeWithMergedPatchSet() throws Exception {
    PushOneCommit.Result changeResult = createChange();
    String changeId = changeResult.getChangeId();
    Change.Id id = changeResult.getChange().getId();

    merge(changeResult);
    setChangeStatus(id, Change.Status.NEW);

    exception.expect(ResourceConflictException.class);
    exception.expectMessage(
        String.format("Cannot delete change %s: patch set 1 is already merged", id));
    gApi.changes().id(changeId).delete();
  }

  @Test
  public void deleteChangeUpdatesIndex() throws Exception {
    PushOneCommit.Result changeResult = createChange();
    String changeId = changeResult.getChangeId();
    Change.Id id = changeResult.getChange().getId();

    ChangeIndex idx = changeIndexCollection.getSearchIndex();

    Optional<ChangeData> result =
        idx.get(id, IndexedChangeQuery.createOptions(indexConfig, 0, 1, ImmutableSet.of()));

    assertThat(result.isPresent()).isTrue();
    gApi.changes().id(changeId).delete();
    result = idx.get(id, IndexedChangeQuery.createOptions(indexConfig, 0, 1, ImmutableSet.of()));
    assertThat(result.isPresent()).isFalse();
  }

  @Test
  public void deleteChangeRemovesDraftComment() throws Exception {
    PushOneCommit.Result r = createChange();

    requestScopeOperations.setApiUser(user.id());

    DraftInput dri = new DraftInput();
    dri.message = "hello";
    dri.path = "a.txt";
    dri.line = 1;

    gApi.changes().id(r.getChangeId()).current().createDraft(dri);
    Change.Id num = r.getChange().getId();

    try (Repository repo = repoManager.openRepository(allUsers)) {
      assertThat(repo.getRefDatabase().getRefsByPrefix(RefNames.refsDraftComments(num, user.id())))
          .isNotEmpty();
    }

    requestScopeOperations.setApiUser(admin.id());

    gApi.changes().id(r.getChangeId()).delete();
    try (Repository repo = repoManager.openRepository(allUsers)) {
      assertThat(repo.getRefDatabase().getRefsByPrefix(RefNames.refsDraftComments(num, user.id())))
          .isEmpty();
    }
  }

  @Test
  public void rebaseUpToDateChange() throws Exception {
    PushOneCommit.Result r = createChange();
    exception.expect(ResourceConflictException.class);
    exception.expectMessage("Change is already up to date");
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).rebase();
  }

  @Test
  public void rebaseConflict() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            PushOneCommit.FILE_NAME,
            "other content",
            "If09d8782c1e59dd0b33de2b1ec3595d69cc10ad5");
    r = push.to("refs/for/master");
    r.assertOkStatus();

    exception.expect(ResourceConflictException.class);
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).rebase();
  }

  @Test
  public void rebaseChangeBase() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = createChange();
    PushOneCommit.Result r3 = createChange();
    RebaseInput ri = new RebaseInput();

    // rebase r3 directly onto master (break dep. towards r2)
    ri.base = "";
    gApi.changes().id(r3.getChangeId()).revision(r3.getCommit().name()).rebase(ri);
    PatchSet ps3 = r3.getPatchSet();
    assertThat(ps3.getId().get()).isEqualTo(2);

    // rebase r2 onto r3 (referenced by ref)
    ri.base = ps3.getId().toRefName();
    gApi.changes().id(r2.getChangeId()).revision(r2.getCommit().name()).rebase(ri);
    PatchSet ps2 = r2.getPatchSet();
    assertThat(ps2.getId().get()).isEqualTo(2);

    // rebase r1 onto r2 (referenced by commit)
    ri.base = ps2.getRevision().get();
    gApi.changes().id(r1.getChangeId()).revision(r1.getCommit().name()).rebase(ri);
    PatchSet ps1 = r1.getPatchSet();
    assertThat(ps1.getId().get()).isEqualTo(2);

    // rebase r1 onto r3 (referenced by change number)
    ri.base = String.valueOf(r3.getChange().getId().get());
    gApi.changes().id(r1.getChangeId()).revision(ps1.getRevision().get()).rebase(ri);
    assertThat(r1.getPatchSetId().get()).isEqualTo(3);
  }

  @Test
  public void rebaseChangeBaseRecursion() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = createChange();

    RebaseInput ri = new RebaseInput();
    ri.base = r2.getCommit().name();
    String expectedMessage =
        "base change "
            + r2.getChangeId()
            + " is a descendant of the current change - recursion not allowed";
    exception.expect(ResourceConflictException.class);
    exception.expectMessage(expectedMessage);
    gApi.changes().id(r1.getChangeId()).revision(r1.getCommit().name()).rebase(ri);
  }

  @Test
  public void rebaseAbandonedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    assertThat(info(changeId).status).isEqualTo(ChangeStatus.NEW);
    gApi.changes().id(changeId).abandon();
    ChangeInfo info = info(changeId);
    assertThat(info.status).isEqualTo(ChangeStatus.ABANDONED);

    exception.expect(ResourceConflictException.class);
    exception.expectMessage("change is abandoned");
    gApi.changes().id(changeId).revision(r.getCommit().name()).rebase();
  }

  @Test
  public void rebaseOntoAbandonedChange() throws Exception {
    // Create two changes both with the same parent
    PushOneCommit.Result r = createChange();
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r2 = createChange();

    // Abandon the first change
    String changeId = r.getChangeId();
    assertThat(info(changeId).status).isEqualTo(ChangeStatus.NEW);
    gApi.changes().id(changeId).abandon();
    ChangeInfo info = info(changeId);
    assertThat(info.status).isEqualTo(ChangeStatus.ABANDONED);

    RebaseInput ri = new RebaseInput();
    ri.base = r.getCommit().name();

    exception.expect(ResourceConflictException.class);
    exception.expectMessage("base change is abandoned: " + changeId);
    gApi.changes().id(r2.getChangeId()).revision(r2.getCommit().name()).rebase(ri);
  }

  @Test
  public void rebaseOntoSelf() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    String commit = r.getCommit().name();
    RebaseInput ri = new RebaseInput();
    ri.base = commit;
    exception.expect(ResourceConflictException.class);
    exception.expectMessage("cannot rebase change onto itself");
    gApi.changes().id(changeId).revision(commit).rebase(ri);
  }

  @Test
  @TestProjectInput(createEmptyCommit = false)
  public void changeNoParentToOneParent() throws Exception {
    // create initial commit with no parent and push it as change, so that patch
    // set 1 has no parent
    RevCommit c = testRepo.commit().message("Initial commit").insertChangeId().create();
    String id = GitUtil.getChangeId(testRepo, c).get();
    testRepo.reset(c);

    PushResult pr = pushHead(testRepo, "refs/for/master", false);
    assertPushOk(pr, "refs/for/master");

    ChangeInfo change = gApi.changes().id(id).get();
    assertThat(change.revisions.get(change.currentRevision).commit.parents).isEmpty();

    // create another initial commit with no parent and push it directly into
    // the remote repository
    c = testRepo.amend(c.getId()).message("Initial Empty Commit").create();
    testRepo.reset(c);
    pr = pushHead(testRepo, "refs/heads/master", false);
    assertPushOk(pr, "refs/heads/master");

    // create a successor commit and push it as second patch set to the change,
    // so that patch set 2 has 1 parent
    RevCommit c2 =
        testRepo
            .commit()
            .message("Initial commit")
            .parent(c)
            .insertChangeId(id.substring(1))
            .create();
    testRepo.reset(c2);

    pr = pushHead(testRepo, "refs/for/master", false);
    assertPushOk(pr, "refs/for/master");

    change = gApi.changes().id(id).get();
    RevisionInfo rev = change.revisions.get(change.currentRevision);
    assertThat(rev.commit.parents).hasSize(1);
    assertThat(rev.commit.parents.get(0).commit).isEqualTo(c.name());

    // check that change kind is correctly detected as REWORK
    assertThat(rev.kind).isEqualTo(ChangeKind.REWORK);
  }

  @Test
  public void pushCommitOfOtherUser() throws Exception {
    // admin pushes commit of user
    PushOneCommit push = pushFactory.create(user.newIdent(), testRepo);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(result.getChangeId()).get();
    assertThat(change.owner._accountId).isEqualTo(admin.id().get());
    CommitInfo commit = change.revisions.get(change.currentRevision).commit;
    assertThat(commit.author.email).isEqualTo(user.email());
    assertThat(commit.committer.email).isEqualTo(user.email());

    // check that the author/committer was added as reviewer
    Collection<AccountInfo> reviewers = change.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(user.id().get());
    assertThat(change.reviewers.get(CC)).isNull();

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.from().getName()).isEqualTo("Administrator (Code Review)");
    assertThat(m.rcpt()).containsExactly(user.getEmailAddress());
    assertThat(m.body()).contains("I'd like you to do a code review");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, admin.email());
  }

  @Test
  public void pushCommitOfOtherUserThatCannotSeeChange() throws Exception {
    // create hidden project that is only visible to administrators
    Project.NameKey p = projectOperations.newProject().create();
    try (ProjectConfigUpdate u = updateProject(p)) {
      Util.allow(u.getConfig(), Permission.READ, adminGroupUuid(), "refs/*");
      Util.block(u.getConfig(), Permission.READ, REGISTERED_USERS, "refs/*");
      u.save();
    }

    // admin pushes commit of user
    TestRepository<InMemoryRepository> repo = cloneProject(p, admin);
    PushOneCommit push = pushFactory.create(user.newIdent(), repo);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(result.getChangeId()).get();
    assertThat(change.owner._accountId).isEqualTo(admin.id().get());
    CommitInfo commit = change.revisions.get(change.currentRevision).commit;
    assertThat(commit.author.email).isEqualTo(user.email());
    assertThat(commit.committer.email).isEqualTo(user.email());

    // check the user cannot see the change
    requestScopeOperations.setApiUser(user.id());
    try {
      gApi.changes().id(result.getChangeId()).get();
      fail("Expected ResourceNotFoundException");
    } catch (ResourceNotFoundException e) {
      // Expected.
    }

    // check that the author/committer was NOT added as reviewer (he can't see
    // the change)
    assertThat(change.reviewers.get(REVIEWER)).isNull();
    assertThat(change.reviewers.get(CC)).isNull();
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void pushCommitWithFooterOfOtherUser() throws Exception {
    // admin pushes commit that references 'user' in a footer
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT
                + "\n\n"
                + FooterConstants.REVIEWED_BY.getName()
                + ": "
                + user.newIdent().toExternalString(),
            PushOneCommit.FILE_NAME,
            PushOneCommit.FILE_CONTENT);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    // check that 'user' was added as reviewer
    ChangeInfo change = gApi.changes().id(result.getChangeId()).get();
    Collection<AccountInfo> reviewers = change.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(user.id().get());
    assertThat(change.reviewers.get(CC)).isNull();

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(user.getEmailAddress());
    assertThat(m.body()).contains("Hello " + user.fullName() + ",\n");
    assertThat(m.body()).contains("I'd like you to do a code review.");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, admin.email());
  }

  @Test
  public void pushCommitWithFooterOfOtherUserThatCannotSeeChange() throws Exception {
    // create hidden project that is only visible to administrators
    Project.NameKey p = projectOperations.newProject().create();
    try (ProjectConfigUpdate u = updateProject(p)) {
      Util.allow(u.getConfig(), Permission.READ, adminGroupUuid(), "refs/*");
      Util.block(u.getConfig(), Permission.READ, REGISTERED_USERS, "refs/*");
      u.save();
    }

    // admin pushes commit that references 'user' in a footer
    TestRepository<InMemoryRepository> repo = cloneProject(p, admin);
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            repo,
            PushOneCommit.SUBJECT
                + "\n\n"
                + FooterConstants.REVIEWED_BY.getName()
                + ": "
                + user.newIdent().toExternalString(),
            PushOneCommit.FILE_NAME,
            PushOneCommit.FILE_CONTENT);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    // check that 'user' cannot see the change
    requestScopeOperations.setApiUser(user.id());
    try {
      gApi.changes().id(result.getChangeId()).get();
      fail("Expected ResourceNotFoundException");
    } catch (ResourceNotFoundException e) {
      // Expected.
    }

    // check that 'user' was NOT added as cc ('user' can't see the change)
    requestScopeOperations.setApiUser(admin.id());
    ChangeInfo change = gApi.changes().id(result.getChangeId()).get();
    assertThat(change.reviewers.get(REVIEWER)).isNull();
    assertThat(change.reviewers.get(CC)).isNull();
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void addReviewerThatCannotSeeChange() throws Exception {
    // create hidden project that is only visible to administrators
    Project.NameKey p = projectOperations.newProject().create();
    try (ProjectConfigUpdate u = updateProject(p)) {
      Util.allow(u.getConfig(), Permission.READ, adminGroupUuid(), "refs/*");
      Util.block(u.getConfig(), Permission.READ, REGISTERED_USERS, "refs/*");
      u.save();
    }

    // create change
    TestRepository<InMemoryRepository> repo = cloneProject(p, admin);
    PushOneCommit push = pushFactory.create(admin.newIdent(), repo);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    // check the user cannot see the change
    requestScopeOperations.setApiUser(user.id());
    try {
      gApi.changes().id(result.getChangeId()).get();
      fail("Expected ResourceNotFoundException");
    } catch (ResourceNotFoundException e) {
      // Expected.
    }

    // try to add user as reviewer
    requestScopeOperations.setApiUser(admin.id());
    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    AddReviewerResult r = gApi.changes().id(result.getChangeId()).addReviewer(in);

    assertThat(r.input).isEqualTo(user.email());
    assertThat(r.error).contains("does not have permission to see this change");
    assertThat(r.reviewers).isNull();
  }

  @Test
  public void addReviewerThatIsInactive() throws Exception {
    PushOneCommit.Result result = createChange();

    String username = name("new-user");
    Account.Id id = accountOperations.newAccount().username(username).inactive().create();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = username;
    AddReviewerResult r = gApi.changes().id(result.getChangeId()).addReviewer(in);

    assertThat(r.input).isEqualTo(in.reviewer);
    assertThat(r.error)
        .isEqualTo(
            "Account '"
                + username
                + "' only matches inactive accounts. To use an inactive account, retry with one of"
                + " the following exact account IDs:\n"
                + id
                + ": Name of user not set ("
                + id
                + ")\n"
                + username
                + " does not identify a registered user or group");
    assertThat(r.reviewers).isNull();
  }

  @Test
  public void addReviewerThatIsInactiveById() throws Exception {
    PushOneCommit.Result result = createChange();

    String username = name("new-user");
    Account.Id id = accountOperations.newAccount().username(username).inactive().create();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = Integer.toString(id.get());
    AddReviewerResult r = gApi.changes().id(result.getChangeId()).addReviewer(in);

    assertThat(r.input).isEqualTo(in.reviewer);
    assertThat(r.error).isNull();
    assertThat(r.reviewers).hasSize(1);
    ReviewerInfo reviewer = r.reviewers.get(0);
    assertThat(reviewer._accountId).isEqualTo(id.get());
    assertThat(reviewer.username).isEqualTo(username);
  }

  @Test
  public void addReviewerThatIsInactiveEmailFallback() throws Exception {
    ConfigInput conf = new ConfigInput();
    conf.enableReviewerByEmail = InheritableBoolean.TRUE;
    gApi.projects().name(project.get()).config(conf);

    PushOneCommit.Result result = createChange();

    String username = "user@domain.com";
    accountOperations.newAccount().username(username).inactive().create();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = username;
    in.state = ReviewerState.CC;
    AddReviewerResult r = gApi.changes().id(result.getChangeId()).addReviewer(in);

    assertThat(r.input).isEqualTo(username);
    assertThat(r.error).isNull();
    // When adding by email, the reviewers field is also empty because we can't
    // render a ReviewerInfo object for a non-account.
    assertThat(r.reviewers).isNull();
  }

  @Test
  public void addReviewer() throws Exception {
    TestTimeUtil.resetWithClockStep(1, SECONDS);
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    String oldETag = rsrc.getETag();
    Timestamp oldTs = rsrc.getChange().getLastUpdatedOn();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(user.getEmailAddress());
    assertThat(m.body()).contains("Hello " + user.fullName() + ",\n");
    assertThat(m.body()).contains("I'd like you to do a code review.");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, admin.email());
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();

    // When NoteDb is enabled adding a reviewer records that user as reviewer
    // in NoteDb. When NoteDb is disabled adding a reviewer results in a dummy 0
    // approval on the change which is treated as CC when the ChangeInfo is
    // created.
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(user.id().get());

    // Ensure ETag and lastUpdatedOn are updated.
    rsrc = parseResource(r);
    assertThat(rsrc.getETag()).isNotEqualTo(oldETag);
    assertThat(rsrc.getChange().getLastUpdatedOn()).isNotEqualTo(oldTs);

    // Change status of reviewer and ensure ETag is updated.
    oldETag = rsrc.getETag();
    accountOperations.account(user.id()).forUpdate().status("new status").update();
    rsrc = parseResource(r);
    assertThat(rsrc.getETag()).isNotEqualTo(oldETag);
  }

  @Test
  public void listReviewers() throws Exception {
    PushOneCommit.Result r = createChange();
    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);
    assertThat(gApi.changes().id(r.getChangeId()).reviewers()).hasSize(1);

    String username1 = name("user1");
    String email1 = username1 + "@example.com";
    accountOperations
        .newAccount()
        .username(username1)
        .preferredEmail(email1)
        .fullname("User 1")
        .create();
    in.reviewer = email1;
    in.state = ReviewerState.CC;
    gApi.changes().id(r.getChangeId()).addReviewer(in);
    assertThat(gApi.changes().id(r.getChangeId()).reviewers().stream().map(a -> a.username))
        .containsExactly(user.username(), username1);
  }

  @Test
  public void notificationsForAddedWorkInProgressReviewers() throws Exception {
    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    ReviewInput batchIn = new ReviewInput();
    batchIn.reviewers = ImmutableList.of(in);

    // Added reviewers not notified by default.
    PushOneCommit.Result r = createWorkInProgressChange();
    gApi.changes().id(r.getChangeId()).addReviewer(in);
    assertThat(sender.getMessages()).hasSize(0);

    // Default notification handling can be overridden.
    r = createWorkInProgressChange();
    in.notify = NotifyHandling.OWNER_REVIEWERS;
    gApi.changes().id(r.getChangeId()).addReviewer(in);
    assertThat(sender.getMessages()).hasSize(1);
    sender.clear();

    // Reviewers added via PostReview also not notified by default.
    // In this case, the child ReviewerInput has a notify=OWNER_REVIEWERS
    // that should be ignored.
    r = createWorkInProgressChange();
    gApi.changes().id(r.getChangeId()).revision("current").review(batchIn);
    assertThat(sender.getMessages()).hasSize(0);

    // Top-level notify property can force notifications when adding reviewer
    // via PostReview.
    r = createWorkInProgressChange();
    batchIn.notify = NotifyHandling.OWNER_REVIEWERS;
    gApi.changes().id(r.getChangeId()).revision("current").review(batchIn);
    assertThat(sender.getMessages()).hasSize(1);
  }

  @Test
  public void addReviewerThatIsNotPerfectMatch() throws Exception {
    TestTimeUtil.resetWithClockStep(1, SECONDS);
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    String oldETag = rsrc.getETag();
    Timestamp oldTs = rsrc.getChange().getLastUpdatedOn();

    // create a group named "ab" with one user: testUser
    String email = "abcd@test.com";
    String fullname = "abcd";
    Account.Id accountIdOfTestUser =
        accountOperations
            .newAccount()
            .username("abcd")
            .preferredEmail(email)
            .fullname(fullname)
            .create();
    String testGroup = groupOperations.newGroup().name("ab").create().get();
    GroupApi groupApi = gApi.groups().id(testGroup);
    groupApi.description("test group");
    groupApi.addMembers(user.fullName());

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = "abc";
    gApi.changes().id(r.getChangeId()).addReviewer(in.reviewer);

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(new Address(fullname, email));
    assertThat(m.body()).contains("Hello " + fullname + ",\n");
    assertThat(m.body()).contains("I'd like you to do a code review.");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, email);
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();

    // When NoteDb is enabled adding a reviewer records that user as reviewer
    // in NoteDb. When NoteDb is disabled adding a reviewer results in a dummy 0
    // approval on the change which is treated as CC when the ChangeInfo is
    // created.
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(accountIdOfTestUser.get());

    // Ensure ETag and lastUpdatedOn are updated.
    rsrc = parseResource(r);
    assertThat(rsrc.getETag()).isNotEqualTo(oldETag);
    assertThat(rsrc.getChange().getLastUpdatedOn()).isNotEqualTo(oldTs);
  }

  @Test
  public void addGroupAsReviewersWhenANotPerfectMatchedUserExists() throws Exception {
    TestTimeUtil.resetWithClockStep(1, SECONDS);
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    String oldETag = rsrc.getETag();
    Timestamp oldTs = rsrc.getChange().getLastUpdatedOn();

    // create a group named "kobe" with one user: lee
    String testUserFullname = "kobebryant";
    accountOperations
        .newAccount()
        .username("kobebryant")
        .preferredEmail("kobebryant@test.com")
        .fullname(testUserFullname)
        .create();

    String myGroupUserEmail = "lee@test.com";
    String myGroupUserFullname = "lee";
    Account.Id accountIdOfGroupUser =
        accountOperations
            .newAccount()
            .username("lee")
            .preferredEmail(myGroupUserEmail)
            .fullname(myGroupUserFullname)
            .create();

    String testGroup = groupOperations.newGroup().name("kobe").create().get();
    GroupApi groupApi = gApi.groups().id(testGroup);
    groupApi.description("test group");
    groupApi.addMembers(myGroupUserFullname);

    // ensure that user "user" is not in the group
    groupApi.removeMembers(testUserFullname);

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = testGroup;
    gApi.changes().id(r.getChangeId()).addReviewer(in.reviewer);

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(new Address(myGroupUserFullname, myGroupUserEmail));
    assertThat(m.body()).contains("Hello " + myGroupUserFullname + ",\n");
    assertThat(m.body()).contains("I'd like you to do a code review.");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, myGroupUserEmail);
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();

    // When NoteDb is enabled adding a reviewer records that user as reviewer
    // in NoteDb. When NoteDb is disabled adding a reviewer results in a dummy 0
    // approval on the change which is treated as CC when the ChangeInfo is
    // created.
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(accountIdOfGroupUser.get());

    // Ensure ETag and lastUpdatedOn are updated.
    rsrc = parseResource(r);
    assertThat(rsrc.getETag()).isNotEqualTo(oldETag);
    assertThat(rsrc.getChange().getLastUpdatedOn()).isNotEqualTo(oldTs);
  }

  @Test
  public void addSelfAsReviewer() throws Exception {
    TestTimeUtil.resetWithClockStep(1, SECONDS);
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    String oldETag = rsrc.getETag();
    Timestamp oldTs = rsrc.getChange().getLastUpdatedOn();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    // There should be no email notification when adding self
    assertThat(sender.getMessages()).isEmpty();

    // When NoteDb is enabled adding a reviewer records that user as reviewer
    // in NoteDb. When NoteDb is disabled adding a reviewer results in a dummy 0
    // approval on the change which is treated as CC when the ChangeInfo is
    // created.
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(user.id().get());

    // Ensure ETag and lastUpdatedOn are updated.
    rsrc = parseResource(r);
    assertThat(rsrc.getETag()).isNotEqualTo(oldETag);
    assertThat(rsrc.getChange().getLastUpdatedOn()).isNotEqualTo(oldTs);
  }

  @Test
  public void implicitlyCcOnNonVotingReviewPgStyle() throws Exception {
    testImplicitlyCcOnNonVotingReviewPgStyle(user);
  }

  @Test
  public void implicitlyCcOnNonVotingReviewForUserWithoutUserNamePgStyle() throws Exception {
    com.google.gerrit.acceptance.TestAccount accountWithoutUsername = accountCreator.create();
    assertThat(accountWithoutUsername.username()).isNull();
    testImplicitlyCcOnNonVotingReviewPgStyle(accountWithoutUsername);
  }

  private void testImplicitlyCcOnNonVotingReviewPgStyle(
      com.google.gerrit.acceptance.TestAccount testAccount) throws Exception {
    PushOneCommit.Result r = createChange();
    requestScopeOperations.setApiUser(testAccount.id());
    assertThat(getReviewerState(r.getChangeId(), testAccount.id())).isEmpty();

    // Exact request format made by PG UI at ddc6b7160fe416fed9e7e3180489d44c82fd64f8.
    ReviewInput in = new ReviewInput();
    in.drafts = DraftHandling.PUBLISH_ALL_REVISIONS;
    in.labels = ImmutableMap.of();
    in.message = "comment";
    in.reviewers = ImmutableList.of();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(in);

    assertThat(getReviewerState(r.getChangeId(), testAccount.id())).hasValue(CC);
  }

  @Test
  public void implicitlyAddReviewerOnVotingReview() throws Exception {
    PushOneCommit.Result r = createChange();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes()
        .id(r.getChangeId())
        .revision(r.getCommit().name())
        .review(ReviewInput.recommend().message("LGTM"));

    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    assertThat(c.reviewers.get(REVIEWER).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(user.id().get());

    // Further test: remove the vote, then comment again. The user should be
    // implicitly re-added to the ReviewerSet, as a CC if we're using NoteDb.
    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).remove();
    c = gApi.changes().id(r.getChangeId()).get();
    assertThat(c.reviewers.values()).isEmpty();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes()
        .id(r.getChangeId())
        .revision(r.getCommit().name())
        .review(new ReviewInput().message("hi"));
    c = gApi.changes().id(r.getChangeId()).get();
    assertThat(c.reviewers.get(CC).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(user.id().get());
  }

  @Test
  public void addReviewerToClosedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(admin.id().get());
    assertThat(c.reviewers).doesNotContainKey(CC);

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    c = gApi.changes().id(r.getChangeId()).get();
    reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).hasSize(2);
    Iterator<AccountInfo> reviewerIt = reviewers.iterator();
    assertThat(reviewerIt.next()._accountId).isEqualTo(admin.id().get());
    assertThat(reviewerIt.next()._accountId).isEqualTo(user.id().get());
    assertThat(c.reviewers).doesNotContainKey(CC);
  }

  @Test
  public void eTagChangesWhenOwnerUpdatesAccountStatus() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    String oldETag = rsrc.getETag();

    accountOperations.account(admin.id()).forUpdate().status("new status").update();
    rsrc = parseResource(r);
    assertThat(rsrc.getETag()).isNotEqualTo(oldETag);
  }

  @Test
  public void emailNotificationForFileLevelComment() throws Exception {
    String changeId = createChange().getChangeId();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeId).addReviewer(in);
    sender.clear();

    ReviewInput review = new ReviewInput();
    ReviewInput.CommentInput comment = new ReviewInput.CommentInput();
    comment.path = PushOneCommit.FILE_NAME;
    comment.side = Side.REVISION;
    comment.message = "comment 1";
    review.comments = new HashMap<>();
    review.comments.put(comment.path, Lists.newArrayList(comment));
    gApi.changes().id(changeId).current().review(review);

    assertThat(sender.getMessages()).hasSize(1);
    Message m = sender.getMessages().get(0);
    assertThat(m.rcpt()).containsExactly(user.getEmailAddress());
  }

  @Test
  public void invalidRange() throws Exception {
    String changeId = createChange().getChangeId();

    ReviewInput review = new ReviewInput();
    ReviewInput.CommentInput comment = new ReviewInput.CommentInput();

    comment.range = new Range();
    comment.range.startLine = 1;
    comment.range.endLine = 1;
    comment.range.startCharacter = -1;
    comment.range.endCharacter = 0;

    comment.path = PushOneCommit.FILE_NAME;
    comment.side = Side.REVISION;
    comment.message = "comment 1";
    review.comments = ImmutableMap.of(comment.path, Lists.newArrayList(comment));

    exception.expect(BadRequestException.class);
    gApi.changes().id(changeId).current().review(review);
  }

  @Test
  public void listVotes() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    Map<String, Short> m =
        gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).votes();

    assertThat(m).hasSize(1);
    assertThat(m).containsEntry("Code-Review", Short.valueOf((short) 2));

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.dislike());

    m = gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).votes();

    assertThat(m).hasSize(1);
    assertThat(m).containsEntry("Code-Review", Short.valueOf((short) -1));
  }

  @Test
  public void removeReviewerNoVotes() throws Exception {
    try (ProjectConfigUpdate u = updateProject(project)) {
      LabelType verified =
          category("Verified", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
      u.getConfig().getLabelSections().put(verified.getName(), verified);
      AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
      String heads = RefNames.REFS_HEADS + "*";
      Util.allow(
          u.getConfig(),
          Permission.forLabel(Util.verified().getName()),
          -1,
          1,
          registeredUsers,
          heads);
      u.save();
    }

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).addReviewer(user.id().toString());

    ChangeInfo c = gApi.changes().id(changeId).get(ListChangesOption.DETAILED_LABELS);
    assertThat(getReviewers(c.reviewers.get(CC))).isEmpty();
    assertThat(getReviewers(c.reviewers.get(REVIEWER))).containsExactly(user.id());

    sender.clear();
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove();
    assertThat(gApi.changes().id(changeId).get().reviewers).isEmpty();

    assertThat(sender.getMessages()).hasSize(1);
    Message message = sender.getMessages().get(0);
    assertThat(message.body()).contains("Removed reviewer " + user.fullName() + ".");
    assertThat(message.body()).doesNotContain("with the following votes");

    // Make sure the reviewer can still be added again.
    gApi.changes().id(changeId).addReviewer(user.id().toString());
    c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(CC))).isEmpty();
    assertThat(getReviewers(c.reviewers.get(REVIEWER))).containsExactly(user.id());

    // Remove again, and then try to remove once more to verify 404 is
    // returned.
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove();
    exception.expect(ResourceNotFoundException.class);
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove();
  }

  @Test
  public void removeReviewer() throws Exception {
    testRemoveReviewer(true);
  }

  @Test
  public void removeNoNotify() throws Exception {
    testRemoveReviewer(false);
  }

  private void testRemoveReviewer(boolean notify) throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).revision(r.getCommit().name()).review(ReviewInput.recommend());

    Collection<AccountInfo> reviewers = gApi.changes().id(changeId).get().reviewers.get(REVIEWER);

    assertThat(reviewers).hasSize(2);
    Iterator<AccountInfo> reviewerIt = reviewers.iterator();
    assertThat(reviewerIt.next()._accountId).isEqualTo(admin.id().get());
    assertThat(reviewerIt.next()._accountId).isEqualTo(user.id().get());

    sender.clear();
    requestScopeOperations.setApiUser(admin.id());
    DeleteReviewerInput input = new DeleteReviewerInput();
    if (!notify) {
      input.notify = NotifyHandling.NONE;
    }
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove(input);

    if (notify) {
      assertThat(sender.getMessages()).hasSize(1);
      Message message = sender.getMessages().get(0);
      assertThat(message.body())
          .contains("Removed reviewer " + user.fullName() + " with the following votes");
      assertThat(message.body()).contains("* Code-Review+1 by " + user.fullName());
    } else {
      assertThat(sender.getMessages()).isEmpty();
    }

    reviewers = gApi.changes().id(changeId).get().reviewers.get(REVIEWER);
    assertThat(reviewers).hasSize(1);
    reviewerIt = reviewers.iterator();
    assertThat(reviewerIt.next()._accountId).isEqualTo(admin.id().get());

    eventRecorder.assertReviewerDeletedEvents(changeId, user.email());
  }

  @Test
  public void removeReviewerNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("remove reviewer not permitted");
    gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).remove();
  }

  @Test
  public void removeReviewerSelfFromMergedChangeNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    recommend(changeId);

    requestScopeOperations.setApiUser(admin.id());
    approve(changeId);
    gApi.changes().id(changeId).revision(r.getCommit().name()).submit();

    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("remove reviewer not permitted");
    gApi.changes().id(r.getChangeId()).reviewer("self").remove();
  }

  @Test
  public void removeReviewerSelfFromAbandonedChangePermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    recommend(changeId);

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).abandon();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).reviewer("self").remove();
    eventRecorder.assertReviewerDeletedEvents(changeId, user.email());
  }

  @Test
  public void removeOtherReviewerFromAbandonedChangeNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    recommend(changeId);

    requestScopeOperations.setApiUser(admin.id());
    approve(changeId);
    gApi.changes().id(changeId).abandon();

    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("remove reviewer not permitted");
    gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).remove();
  }

  @Test
  public void deleteVote() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());

    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote("Code-Review");

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message msg = messages.get(0);
    assertThat(msg.rcpt()).containsExactly(user.getEmailAddress());
    assertThat(msg.body()).contains(admin.fullName() + " has removed a vote from this change.");
    assertThat(msg.body())
        .contains("Removed Code-Review+1 by " + user.fullName() + " <" + user.email() + ">\n");

    Map<String, Short> m =
        gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).votes();

    // Dummy 0 approval on the change to block vote copying to this patch set.
    assertThat(m).containsExactly("Code-Review", Short.valueOf((short) 0));

    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();

    ChangeMessageInfo message = Iterables.getLast(c.messages);
    assertThat(message.author._accountId).isEqualTo(admin.id().get());
    assertThat(message.message).isEqualTo("Removed Code-Review+1 by User <user@example.com>\n");
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id(), user.id()));
  }

  @Test
  public void deleteVoteNotifyNone() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());

    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    DeleteVoteInput in = new DeleteVoteInput();
    in.label = "Code-Review";
    in.notify = NotifyHandling.NONE;
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void deleteVoteNotifyAccount() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    DeleteVoteInput in = new DeleteVoteInput();
    in.label = "Code-Review";
    in.notify = NotifyHandling.NONE;

    // notify unrelated account as TO
    String email = "user2@example.com";
    accountOperations
        .newAccount()
        .username("user2")
        .preferredEmail(email)
        .fullname("User2")
        .create();
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    in.notifyDetails = new HashMap<>();
    in.notifyDetails.put(RecipientType.TO, new NotifyInfo(ImmutableList.of(email)));
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertNotifyTo(email, "User2");

    // notify unrelated account as CC
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    in.notifyDetails = new HashMap<>();
    in.notifyDetails.put(RecipientType.CC, new NotifyInfo(ImmutableList.of(email)));
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertNotifyCc(email, "User2");

    // notify unrelated account as BCC
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    in.notifyDetails = new HashMap<>();
    in.notifyDetails.put(RecipientType.BCC, new NotifyInfo(ImmutableList.of(email)));
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertNotifyBcc(email, "User2");
  }

  @Test
  public void deleteVoteNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("delete vote not permitted");
    gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).deleteVote("Code-Review");
  }

  @Test
  public void nonVotingReviewerStaysAfterSubmit() throws Exception {
    LabelType verified =
        category("Verified", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getLabelSections().put(verified.getName(), verified);
      String heads = "refs/heads/*";
      AccountGroup.UUID owners = systemGroupBackend.getGroup(CHANGE_OWNER).getUUID();
      AccountGroup.UUID registered = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
      Util.allow(u.getConfig(), Permission.forLabel(verified.getName()), -1, 1, owners, heads);
      Util.allow(u.getConfig(), Permission.forLabel("Code-Review"), -2, +2, registered, heads);
      u.save();
    }

    // Set Code-Review+2 and Verified+1 as admin (change owner)
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    String commit = r.getCommit().name();
    ReviewInput input = ReviewInput.approve();
    input.label(verified.getName(), 1);
    gApi.changes().id(changeId).revision(commit).review(input);

    // Reviewers should only be "admin"
    ChangeInfo c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id()));
    assertThat(c.reviewers.get(CC)).isNull();

    // Add the user as reviewer
    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeId).addReviewer(in);
    c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id(), user.id()));

    // Approve the change as user, then remove the approval
    // (only to confirm that the user does have Code-Review+2 permission)
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).revision(commit).review(ReviewInput.approve());
    gApi.changes().id(changeId).revision(commit).review(ReviewInput.noScore());

    // Submit the change
    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).revision(commit).submit();

    // User should still be on the change
    c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id(), user.id()));
  }

  @Test
  public void createEmptyChange() throws Exception {
    ChangeInput in = new ChangeInput();
    in.branch = Constants.MASTER;
    in.subject = "Create a change from the API";
    in.project = project.get();
    ChangeInfo info = gApi.changes().create(in).get();
    assertThat(info.project).isEqualTo(in.project);
    assertThat(RefNames.fullName(info.branch)).isEqualTo(RefNames.fullName(in.branch));
    assertThat(info.subject).isEqualTo(in.subject);
    assertThat(Iterables.getOnlyElement(info.messages).message).isEqualTo("Uploaded patch set 1.");
  }

  @Test
  public void queryChangesNoQuery() throws Exception {
    PushOneCommit.Result r = createChange();
    List<ChangeInfo> results = gApi.changes().query().get();
    assertThat(results.size()).isAtLeast(1);
    List<Integer> ids = new ArrayList<>(results.size());
    for (int i = 0; i < results.size(); i++) {
      ChangeInfo info = results.get(i);
      if (i == 0) {
        assertThat(info._number).isEqualTo(r.getChange().getId().get());
      }
      assertThat(Change.Status.forChangeStatus(info.status).isOpen()).isTrue();
      ids.add(info._number);
    }
    assertThat(ids).contains(r.getChange().getId().get());
  }

  @Test
  public void queryChangesNoResults() throws Exception {
    createChange();
    assertThat(query("message:test")).isNotEmpty();
    assertThat(query("message:{" + getClass().getName() + "fhqwhgads}")).isEmpty();
  }

  @Test
  public void queryChanges() throws Exception {
    PushOneCommit.Result r1 = createChange();
    createChange();
    List<ChangeInfo> results = query("project:{" + project.get() + "} " + r1.getChangeId());
    assertThat(Iterables.getOnlyElement(results).changeId).isEqualTo(r1.getChangeId());
  }

  @Test
  public void queryChangesLimit() throws Exception {
    createChange();
    PushOneCommit.Result r2 = createChange();
    List<ChangeInfo> results = gApi.changes().query().withLimit(1).get();
    assertThat(results).hasSize(1);
    assertThat(Iterables.getOnlyElement(results).changeId).isEqualTo(r2.getChangeId());
  }

  @Test
  public void queryChangesNoLimit() throws Exception {
    allowGlobalCapabilities(
        SystemGroupBackend.REGISTERED_USERS, 0, 2, GlobalCapability.QUERY_LIMIT);
    for (int i = 0; i < 3; i++) {
      createChange();
    }
    List<ChangeInfo> resultsWithDefaultLimit = gApi.changes().query().get();
    List<ChangeInfo> resultsWithNoLimit = gApi.changes().query().withNoLimit().get();
    assertThat(resultsWithDefaultLimit).hasSize(2);
    assertThat(resultsWithNoLimit.size()).isAtLeast(3);
  }

  @Test
  public void queryChangesStart() throws Exception {
    PushOneCommit.Result r1 = createChange();
    createChange();
    List<ChangeInfo> results =
        gApi.changes().query("project:{" + project.get() + "}").withStart(1).get();
    assertThat(Iterables.getOnlyElement(results).changeId).isEqualTo(r1.getChangeId());
  }

  @Test
  public void queryChangesNoOptions() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeInfo result = Iterables.getOnlyElement(query(r.getChangeId()));
    assertThat(result.labels).isNull();
    assertThat(result.messages).isNull();
    assertThat(result.revisions).isNull();
    assertThat(result.actions).isNull();
  }

  @Test
  public void queryChangesOptions() throws Exception {
    PushOneCommit.Result r = createChange();

    ChangeInfo result = Iterables.getOnlyElement(gApi.changes().query(r.getChangeId()).get());
    assertThat(result.labels).isNull();
    assertThat(result.messages).isNull();
    assertThat(result.actions).isNull();
    assertThat(result.revisions).isNull();

    result =
        Iterables.getOnlyElement(
            gApi.changes()
                .query(r.getChangeId())
                .withOptions(
                    ALL_REVISIONS, CHANGE_ACTIONS, CURRENT_ACTIONS, DETAILED_LABELS, MESSAGES)
                .get());
    assertThat(Iterables.getOnlyElement(result.labels.keySet())).isEqualTo("Code-Review");
    assertThat(result.messages).hasSize(1);
    assertThat(result.actions).isNotEmpty();

    RevisionInfo rev = Iterables.getOnlyElement(result.revisions.values());
    assertThat(rev._number).isEqualTo(r.getPatchSetId().get());
    assertThat(rev.created).isNotNull();
    assertThat(rev.uploader._accountId).isEqualTo(admin.id().get());
    assertThat(rev.ref).isEqualTo(r.getPatchSetId().toRefName());
    assertThat(rev.actions).isNotEmpty();
  }

  @Test
  public void queryChangesOwnerWithDifferentUsers() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(
            Iterables.getOnlyElement(query("project:{" + project.get() + "} owner:self")).changeId)
        .isEqualTo(r.getChangeId());
    requestScopeOperations.setApiUser(user.id());
    assertThat(query("owner:self project:{" + project.get() + "}")).isEmpty();
  }

  private static class OperatorModule extends AbstractModule {
    @Override
    public void configure() {
      bind(ChangeOperatorFactory.class)
          .annotatedWith(Exports.named("mytopic"))
          .toInstance((cqb, value) -> new MyTopicPredicate(value));
    }

    private static class MyTopicPredicate extends PostFilterPredicate<ChangeData> {
      MyTopicPredicate(String value) {
        super("mytopic", value);
      }

      @Override
      public boolean match(ChangeData cd) {
        return Objects.equals(cd.change().getTopic(), value);
      }

      @Override
      public int getCost() {
        return 2;
      }
    }
  }

  @Test
  public void queryChangesPluginOperator() throws Exception {
    PushOneCommit.Result r = createChange();
    String query = "mytopic_myplugin:foo";
    String expectedMessage = "Unsupported operator mytopic_myplugin:foo";
    assertThatQueryException(query).hasMessageThat().isEqualTo(expectedMessage);

    try (AutoCloseable ignored = installPlugin("myplugin", OperatorModule.class)) {
      assertThat(query(query)).isEmpty();
      gApi.changes().id(r.getChangeId()).topic("foo");
      assertThat(query(query).stream().map(i -> i.changeId)).containsExactly(r.getChangeId());
    }

    assertThatQueryException(query).hasMessageThat().isEqualTo(expectedMessage);
  }

  @Test
  public void checkReviewedFlagBeforeAndAfterReview() throws Exception {
    PushOneCommit.Result r = createChange();
    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    requestScopeOperations.setApiUser(user.id());
    assertThat(get(r.getChangeId(), REVIEWED).reviewed).isNull();

    revision(r).review(ReviewInput.recommend());
    assertThat(get(r.getChangeId(), REVIEWED).reviewed).isTrue();
  }

  @Test
  public void topic() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
    gApi.changes().id(r.getChangeId()).topic("mytopic");
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("mytopic");
    gApi.changes().id(r.getChangeId()).topic("");
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
  }

  @Test
  public void editTopicWithoutPermissionNotAllowed() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("edit topic name not permitted");
    gApi.changes().id(r.getChangeId()).topic("mytopic");
  }

  @Test
  public void editTopicWithPermissionAllowed() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
    grant(project, "refs/heads/master", Permission.EDIT_TOPIC_NAME, false, REGISTERED_USERS);
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).topic("mytopic");
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("mytopic");
  }

  @Test
  public void submitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String id = r.getChangeId();

    ChangeInfo c = gApi.changes().id(r.getChangeId()).info();
    assertThat(c.submitted).isNull();
    assertThat(c.submitter).isNull();

    gApi.changes().id(id).current().review(ReviewInput.approve());
    gApi.changes().id(id).current().submit();

    c = gApi.changes().id(r.getChangeId()).info();
    assertThat(c.submitted).isNotNull();
    assertThat(c.submitter).isNotNull();
    assertThat(c.submitter._accountId).isEqualTo(atrScope.get().getUser().getAccountId().get());
  }

  @Test
  public void submitStaleChange() throws Exception {
    PushOneCommit.Result r = createChange();

    disableChangeIndexWrites();
    try {
      r = amendChange(r.getChangeId());
    } finally {
      enableChangeIndexWrites();
    }

    gApi.changes().id(r.getChangeId()).current().review(ReviewInput.approve());

    gApi.changes().id(r.getChangeId()).current().submit();
    assertThat(gApi.changes().id(r.getChangeId()).info().status).isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void submitNotAllowedWithoutPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    requestScopeOperations.setApiUser(user.id());
    exception.expect(AuthException.class);
    exception.expectMessage("submit not permitted");
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();
  }

  @Test
  public void submitAllowedWithPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    grant(project, "refs/heads/master", Permission.SUBMIT, false, REGISTERED_USERS);
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();
    assertThat(gApi.changes().id(r.getChangeId()).info().status).isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void check() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).get().problems).isNull();
    assertThat(gApi.changes().id(r.getChangeId()).get(CHECK).problems).isEmpty();
  }

  @Test
  public void commitFooters() throws Exception {
    LabelType verified =
        category("Verified", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    LabelType custom1 =
        category("Custom1", value(1, "Positive"), value(0, "No score"), value(-1, "Negative"));
    LabelType custom2 =
        category("Custom2", value(1, "Positive"), value(0, "No score"), value(-1, "Negative"));
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getLabelSections().put(verified.getName(), verified);
      u.getConfig().getLabelSections().put(custom1.getName(), custom1);
      u.getConfig().getLabelSections().put(custom2.getName(), custom2);
      String heads = "refs/heads/*";
      AccountGroup.UUID anon = systemGroupBackend.getGroup(ANONYMOUS_USERS).getUUID();
      Util.allow(u.getConfig(), Permission.forLabel("Verified"), -1, 1, anon, heads);
      Util.allow(u.getConfig(), Permission.forLabel("Custom1"), -1, 1, anon, heads);
      Util.allow(u.getConfig(), Permission.forLabel("Custom2"), -1, 1, anon, heads);
      u.save();
    }

    PushOneCommit.Result r1 = createChange();
    r1.assertOkStatus();
    PushOneCommit.Result r2 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "new content", r1.getChangeId())
            .to("refs/for/master");
    r2.assertOkStatus();

    ReviewInput in = new ReviewInput();
    in.label("Code-Review", 1);
    in.label("Verified", 1);
    in.label("Custom1", -1);
    in.label("Custom2", 1);
    gApi.changes().id(r2.getChangeId()).current().review(in);

    ChangeInfo actual = gApi.changes().id(r2.getChangeId()).get(ALL_REVISIONS, COMMIT_FOOTERS);
    assertThat(actual.revisions).hasSize(2);

    // No footers except on latest patch set.
    assertThat(actual.revisions.get(r1.getCommit().getName()).commitWithFooters).isNull();

    List<String> footers =
        new ArrayList<>(
            Arrays.asList(
                actual.revisions.get(r2.getCommit().getName()).commitWithFooters.split("\\n")));
    // remove subject + blank line
    footers.remove(0);
    footers.remove(0);

    List<String> expectedFooters =
        Arrays.asList(
            "Change-Id: " + r2.getChangeId(),
            "Reviewed-on: "
                + canonicalWebUrl.get()
                + "c/"
                + project.get()
                + "/+/"
                + r2.getChange().getId(),
            "Reviewed-by: Administrator <admin@example.com>",
            "Custom2: Administrator <admin@example.com>",
            "Tested-by: Administrator <admin@example.com>");

    assertThat(footers).containsExactlyElementsIn(expectedFooters);
  }

  @Test
  public void customCommitFooters() throws Exception {
    PushOneCommit.Result change = createChange();
    RegistrationHandle handle =
        changeMessageModifiers.add(
            "gerrit",
            (newCommitMessage, original, mergeTip, destination) -> {
              assertThat(original.getName()).isNotEqualTo(mergeTip.getName());
              return newCommitMessage + "Custom: " + destination.get();
            });
    ChangeInfo actual;
    try {
      actual = gApi.changes().id(change.getChangeId()).get(ALL_REVISIONS, COMMIT_FOOTERS);
    } finally {
      handle.remove();
    }
    List<String> footers =
        new ArrayList<>(
            Arrays.asList(
                actual.revisions.get(change.getCommit().getName()).commitWithFooters.split("\\n")));
    // remove subject + blank line
    footers.remove(0);
    footers.remove(0);

    List<String> expectedFooters =
        Arrays.asList(
            "Change-Id: " + change.getChangeId(),
            "Reviewed-on: "
                + canonicalWebUrl.get()
                + "c/"
                + project.get()
                + "/+/"
                + change.getChange().getId(),
            "Custom: refs/heads/master");
    assertThat(footers).containsExactlyElementsIn(expectedFooters);
  }

  @Test
  public void defaultSearchDoesNotTouchDatabase() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    PushOneCommit.Result r1 = createChange();
    gApi.changes()
        .id(r1.getChangeId())
        .revision(r1.getCommit().name())
        .review(ReviewInput.approve());
    gApi.changes().id(r1.getChangeId()).revision(r1.getCommit().name()).submit();

    createChange();

    requestScopeOperations.setApiUser(user.id());
    try (AutoCloseable ignored = disableNoteDb()) {
      assertThat(
              gApi.changes()
                  .query()
                  .withQuery("project:{" + project.get() + "} (status:open OR status:closed)")
                  // Options should match defaults in AccountDashboardScreen.
                  .withOption(LABELS)
                  .withOption(DETAILED_ACCOUNTS)
                  .withOption(REVIEWED)
                  .get())
          .hasSize(2);
    }
  }

  @Test
  public void votable() throws Exception {
    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();
    gApi.changes().id(triplet).addReviewer(user.username());
    ChangeInfo c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    LabelInfo codeReview = c.labels.get("Code-Review");
    assertThat(codeReview.all).hasSize(1);
    ApprovalInfo approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.value).isEqualTo(0);

    try (ProjectConfigUpdate u = updateProject(project)) {
      Util.blockLabel(u.getConfig(), "Code-Review", REGISTERED_USERS, "refs/heads/*");
      u.save();
    }

    c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    codeReview = c.labels.get("Code-Review");
    assertThat(codeReview.all).hasSize(1);
    approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.value).isNull();
  }

  @Test
  @GerritConfig(name = "gerrit.editGpgKeys", value = "true")
  @GerritConfig(name = "receive.enableSignedPush", value = "true")
  public void pushCertificates() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = amendChange(r1.getChangeId());

    ChangeInfo info = gApi.changes().id(r1.getChangeId()).get(ALL_REVISIONS, PUSH_CERTIFICATES);

    RevisionInfo rev1 = info.revisions.get(r1.getCommit().name());
    assertThat(rev1).isNotNull();
    assertThat(rev1.pushCertificate).isNotNull();
    assertThat(rev1.pushCertificate.certificate).isNull();
    assertThat(rev1.pushCertificate.key).isNull();

    RevisionInfo rev2 = info.revisions.get(r2.getCommit().name());
    assertThat(rev2).isNotNull();
    assertThat(rev2.pushCertificate).isNotNull();
    assertThat(rev2.pushCertificate.certificate).isNull();
    assertThat(rev2.pushCertificate.key).isNull();
  }

  @Test
  public void anonymousRestApi() throws Exception {
    requestScopeOperations.setApiUserAnonymous();
    PushOneCommit.Result r = createChange();

    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.changeId).isEqualTo(r.getChangeId());

    String triplet = project.get() + "~master~" + r.getChangeId();
    info = gApi.changes().id(triplet).get();
    assertThat(info.changeId).isEqualTo(r.getChangeId());

    info = gApi.changes().id(info._number).get();
    assertThat(info.changeId).isEqualTo(r.getChangeId());

    exception.expect(AuthException.class);
    gApi.changes().id(triplet).current().review(ReviewInput.approve());
  }

  @Test
  public void noteDbCommitsOnPatchSetCreation() throws Exception {
    PushOneCommit.Result r = createChange();
    pushFactory
        .create(admin.newIdent(), testRepo, PushOneCommit.SUBJECT, "b.txt", "4711", r.getChangeId())
        .to("refs/for/master")
        .assertOkStatus();
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      RevCommit commitPatchSetCreation =
          rw.parseCommit(repo.exactRef(changeMetaRef(new Change.Id(c._number))).getObjectId());

      assertThat(commitPatchSetCreation.getShortMessage()).isEqualTo("Create patch set 2");
      PersonIdent expectedAuthor =
          changeNoteUtil.newIdent(getAccount(admin.id()), c.updated, serverIdent.get());
      assertThat(commitPatchSetCreation.getAuthorIdent()).isEqualTo(expectedAuthor);
      assertThat(commitPatchSetCreation.getCommitterIdent())
          .isEqualTo(new PersonIdent(serverIdent.get(), c.updated));
      assertThat(commitPatchSetCreation.getParentCount()).isEqualTo(1);

      RevCommit commitChangeCreation = rw.parseCommit(commitPatchSetCreation.getParent(0));
      assertThat(commitChangeCreation.getShortMessage()).isEqualTo("Create change");
      expectedAuthor =
          changeNoteUtil.newIdent(getAccount(admin.id()), c.created, serverIdent.get());
      assertThat(commitChangeCreation.getAuthorIdent()).isEqualTo(expectedAuthor);
      assertThat(commitChangeCreation.getCommitterIdent())
          .isEqualTo(new PersonIdent(serverIdent.get(), c.created));
      assertThat(commitChangeCreation.getParentCount()).isEqualTo(0);
    }
  }

  @Test
  public void createEmptyChangeOnNonExistingBranch() throws Exception {
    ChangeInput in = new ChangeInput();
    in.branch = "foo";
    in.subject = "Create a change on new branch from the API";
    in.project = project.get();
    in.newBranch = true;
    ChangeInfo info = gApi.changes().create(in).get();
    assertThat(info.project).isEqualTo(in.project);
    assertThat(RefNames.fullName(info.branch)).isEqualTo(RefNames.fullName(in.branch));
    assertThat(info.subject).isEqualTo(in.subject);
    assertThat(Iterables.getOnlyElement(info.messages).message).isEqualTo("Uploaded patch set 1.");
  }

  @Test
  public void createEmptyChangeOnExistingBranchWithNewBranch() throws Exception {
    ChangeInput in = new ChangeInput();
    in.branch = Constants.MASTER;
    in.subject = "Create a change on new branch from the API";
    in.project = project.get();
    in.newBranch = true;

    exception.expect(ResourceConflictException.class);
    gApi.changes().create(in).get();
  }

  @Test
  public void createNewPatchSetWithoutPermission() throws Exception {
    // Create new project with clean permissions
    Project.NameKey p = projectOperations.newProject().create();

    // Clone separate repositories of the same project as admin and as user
    TestRepository<InMemoryRepository> adminTestRepo = cloneProject(p, admin);
    TestRepository<InMemoryRepository> userTestRepo = cloneProject(p, user);

    // Block default permission
    block(p, "refs/for/*", Permission.ADD_PATCH_SET, REGISTERED_USERS);

    // Create change as admin
    PushOneCommit push = pushFactory.create(admin.newIdent(), adminTestRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    r1.assertOkStatus();

    // Fetch change
    GitUtil.fetch(userTestRepo, r1.getPatchSet().getRefName() + ":ps");
    userTestRepo.reset("ps");

    // Amend change as user
    PushOneCommit.Result r2 = amendChange(r1.getChangeId(), "refs/for/master", user, userTestRepo);
    r2.assertErrorStatus("cannot add patch set to " + r1.getChange().getId().id + ".");
  }

  @Test
  public void createNewSetPatchWithPermission() throws Exception {
    // Clone separate repositories of the same project as admin and as user
    TestRepository<?> adminTestRepo = cloneProject(project, admin);
    TestRepository<?> userTestRepo = cloneProject(project, user);

    // Create change as admin
    PushOneCommit push = pushFactory.create(admin.newIdent(), adminTestRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    r1.assertOkStatus();

    // Fetch change
    GitUtil.fetch(userTestRepo, r1.getPatchSet().getRefName() + ":ps");
    userTestRepo.reset("ps");

    // Amend change as user
    PushOneCommit.Result r2 = amendChange(r1.getChangeId(), "refs/for/master", user, userTestRepo);
    r2.assertOkStatus();
  }

  @Test
  public void createNewPatchSetAsOwnerWithoutPermission() throws Exception {
    // Create new project with clean permissions
    Project.NameKey p = projectOperations.newProject().create();
    // Clone separate repositories of the same project as admin and as user
    TestRepository<?> adminTestRepo = cloneProject(project, admin);

    // Block default permission
    block(p, "refs/for/*", Permission.ADD_PATCH_SET, REGISTERED_USERS);

    // Create change as admin
    PushOneCommit push = pushFactory.create(admin.newIdent(), adminTestRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    r1.assertOkStatus();

    // Fetch change
    GitUtil.fetch(adminTestRepo, r1.getPatchSet().getRefName() + ":ps");
    adminTestRepo.reset("ps");

    // Amend change as admin
    PushOneCommit.Result r2 =
        amendChange(r1.getChangeId(), "refs/for/master", admin, adminTestRepo);
    r2.assertOkStatus();
  }

  @Test
  public void createMergePatchSet() throws Exception {
    PushOneCommit.Result start = pushTo("refs/heads/master");
    start.assertOkStatus();
    // create a change for master
    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    String changeId = r.getChangeId();

    testRepo.reset(start.getCommit());
    PushOneCommit.Result currentMaster = pushTo("refs/heads/master");
    currentMaster.assertOkStatus();
    String parent = currentMaster.getCommit().getName();

    // push a commit into dev branch
    createBranch("dev");
    PushOneCommit.Result changeA =
        pushFactory
            .create(user.newIdent(), testRepo, "change A", "A.txt", "A content")
            .to("refs/heads/dev");
    changeA.assertOkStatus();
    MergeInput mergeInput = new MergeInput();
    mergeInput.source = "dev";
    MergePatchSetInput in = new MergePatchSetInput();
    in.merge = mergeInput;
    in.subject = "update change by merge ps2";
    gApi.changes().id(changeId).createMergePatchSet(in);
    ChangeInfo changeInfo =
        gApi.changes().id(changeId).get(ALL_REVISIONS, CURRENT_COMMIT, CURRENT_REVISION);
    assertThat(changeInfo.revisions).hasSize(2);
    assertThat(changeInfo.subject).isEqualTo(in.subject);
    assertThat(changeInfo.revisions.get(changeInfo.currentRevision).commit.parents.get(0).commit)
        .isEqualTo(parent);
  }

  @Test
  public void createMergePatchSetInheritParent() throws Exception {
    PushOneCommit.Result start = pushTo("refs/heads/master");
    start.assertOkStatus();
    // create a change for master
    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    String changeId = r.getChangeId();
    String parent = r.getCommit().getParent(0).getName();

    // advance master branch
    testRepo.reset(start.getCommit());
    PushOneCommit.Result currentMaster = pushTo("refs/heads/master");
    currentMaster.assertOkStatus();

    // push a commit into dev branch
    createBranch("dev");
    PushOneCommit.Result changeA =
        pushFactory
            .create(user.newIdent(), testRepo, "change A", "A.txt", "A content")
            .to("refs/heads/dev");
    changeA.assertOkStatus();
    MergeInput mergeInput = new MergeInput();
    mergeInput.source = "dev";
    MergePatchSetInput in = new MergePatchSetInput();
    in.merge = mergeInput;
    in.subject = "update change by merge ps2 inherit parent of ps1";
    in.inheritParent = true;
    gApi.changes().id(changeId).createMergePatchSet(in);
    ChangeInfo changeInfo =
        gApi.changes().id(changeId).get(ALL_REVISIONS, CURRENT_COMMIT, CURRENT_REVISION);

    assertThat(changeInfo.revisions).hasSize(2);
    assertThat(changeInfo.subject).isEqualTo(in.subject);
    assertThat(changeInfo.revisions.get(changeInfo.currentRevision).commit.parents.get(0).commit)
        .isEqualTo(parent);
    assertThat(changeInfo.revisions.get(changeInfo.currentRevision).commit.parents.get(0).commit)
        .isNotEqualTo(currentMaster.getCommit().getName());
  }

  @Test
  public void createMergePatchSetCannotBaseOnInvisibleChange() throws Exception {
    RevCommit initialHead = getRemoteHead();
    createBranch("foo");
    createBranch("bar");

    // Create a merged commit on 'foo' branch.
    merge(createChange("refs/for/foo"));

    // Create the base change on 'bar' branch.
    testRepo.reset(initialHead);
    String baseChange = createChange("refs/for/bar").getChangeId();
    gApi.changes().id(baseChange).setPrivate(true, "set private");

    // Create the destination change on 'master' branch.
    requestScopeOperations.setApiUser(user.id());
    testRepo.reset(initialHead);
    String changeId = createChange().getChangeId();

    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage("Read not permitted for " + baseChange);
    gApi.changes().id(changeId).createMergePatchSet(createMergePatchSetInput(baseChange));
  }

  @Test
  public void createMergePatchSetBaseOnChange() throws Exception {
    RevCommit initialHead = getRemoteHead();
    createBranch("foo");
    createBranch("bar");

    // Create a merged commit on 'foo' branch.
    merge(createChange("refs/for/foo"));

    // Create the base change on 'bar' branch.
    testRepo.reset(initialHead);
    PushOneCommit.Result result = createChange("refs/for/bar");
    String baseChange = result.getChangeId();
    String expectedParent = result.getCommit().getName();

    // Create the destination change on 'master' branch.
    testRepo.reset(initialHead);
    String changeId = createChange().getChangeId();

    gApi.changes().id(changeId).createMergePatchSet(createMergePatchSetInput(baseChange));

    ChangeInfo changeInfo =
        gApi.changes().id(changeId).get(ALL_REVISIONS, CURRENT_COMMIT, CURRENT_REVISION);
    assertThat(changeInfo.revisions).hasSize(2);
    assertThat(changeInfo.subject).isEqualTo("create ps2");
    assertThat(changeInfo.revisions.get(changeInfo.currentRevision).commit.parents.get(0).commit)
        .isEqualTo(expectedParent);
  }

  private MergePatchSetInput createMergePatchSetInput(String baseChange) {
    MergeInput mergeInput = new MergeInput();
    mergeInput.source = "foo";
    MergePatchSetInput in = new MergePatchSetInput();
    in.merge = mergeInput;
    in.subject = "create ps2";
    in.inheritParent = false;
    in.baseChange = baseChange;
    return in;
  }

  @Test
  public void checkLabelsForUnsubmittedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ChangeStatus.NEW);
    assertThat(change.labels.keySet()).containsExactly("Code-Review");
    assertThat(change.permittedLabels.keySet()).containsExactly("Code-Review");

    // add new label and assert that it's returned for existing changes
    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    LabelType verified = Util.verified();
    String heads = RefNames.REFS_HEADS + "*";

    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getLabelSections().put(verified.getName(), verified);
      Util.allow(
          u.getConfig(), Permission.forLabel(verified.getName()), -1, 1, registeredUsers, heads);
      u.save();
    }

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly("Code-Review", "Verified");
    assertThat(change.permittedLabels.keySet()).containsExactly("Code-Review", "Verified");
    assertPermitted(change, "Code-Review", -2, -1, 0, 1, 2);
    assertPermitted(change, "Verified", -1, 0, 1);

    // add an approval on the new label
    gApi.changes()
        .id(r.getChangeId())
        .revision(r.getCommit().name())
        .review(new ReviewInput().label(verified.getName(), verified.getMax().getValue()));

    try (ProjectConfigUpdate u = updateProject(project)) {
      // remove label and assert that it's no longer returned for existing
      // changes, even if there is an approval for it
      u.getConfig().getLabelSections().remove(verified.getName());
      Util.remove(u.getConfig(), Permission.forLabel(verified.getName()), registeredUsers, heads);
      u.save();
    }

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly("Code-Review");
    assertThat(change.permittedLabels.keySet()).containsExactly("Code-Review");

    // abandon the change and see that the returned labels stay the same
    // while all permitted labels disappear.
    gApi.changes().id(r.getChangeId()).abandon();
    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ChangeStatus.ABANDONED);
    assertThat(change.labels.keySet()).containsExactly("Code-Review");
    assertThat(change.permittedLabels).isEmpty();
  }

  @Test
  public void checkLabelsForMergedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ChangeStatus.MERGED);
    assertThat(change.labels.keySet()).containsExactly("Code-Review");
    assertThat(change.permittedLabels.keySet()).containsExactly("Code-Review");
    assertPermitted(change, "Code-Review", 2);

    LabelType verified = Util.verified();
    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    String heads = RefNames.REFS_HEADS + "*";

    // add new label and assert that it's returned for existing changes
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getLabelSections().put(verified.getName(), verified);
      Util.allow(
          u.getConfig(), Permission.forLabel(verified.getName()), -1, 1, registeredUsers, heads);
      u.save();
    }

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly("Code-Review", "Verified");
    assertThat(change.permittedLabels.keySet()).containsExactly("Code-Review", "Verified");
    assertPermitted(change, "Code-Review", 2);
    assertPermitted(change, "Verified", 0, 1);

    // ignore the new label by Prolog submit rule and assert that the label is
    // no longer returned
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":config");
    testRepo.reset("config");
    PushOneCommit push2 =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            "Ignore Verified",
            "rules.pl",
            "submit_rule(submit(CR)) :-\n  gerrit:max_with_block(-2, 2, 'Code-Review', CR).");
    push2.to(RefNames.REFS_CONFIG);

    change = gApi.changes().id(r.getChangeId()).get();
    assertPermitted(change, "Code-Review", 2);
    assertPermitted(change, "Verified");

    // add an approval on the new label and assert that the label is now
    // returned although it is ignored by the Prolog submit rule and hence not
    // included in the submit records
    gApi.changes()
        .id(r.getChangeId())
        .revision(r.getCommit().name())
        .review(new ReviewInput().label(verified.getName(), verified.getMax().getValue()));

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly("Code-Review", "Verified");
    assertPermitted(change, "Code-Review", 2);
    assertPermitted(change, "Verified");

    // remove label and assert that it's no longer returned for existing
    // changes, even if there is an approval for it
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getLabelSections().remove(verified.getName());
      Util.remove(u.getConfig(), Permission.forLabel(verified.getName()), registeredUsers, heads);
      u.save();
    }

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly("Code-Review");
    assertThat(change.permittedLabels.keySet()).containsExactly("Code-Review");
    assertPermitted(change, "Code-Review", 2);
  }

  @Test
  public void checkLabelsForMergedChangeWithNonAuthorCodeReview() throws Exception {
    // Configure Non-Author-Code-Review
    RevCommit oldHead = getRemoteHead();
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":config");
    testRepo.reset("config");
    PushOneCommit push2 =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            "Configure Non-Author-Code-Review",
            "rules.pl",
            "submit_rule(S) :-\n"
                + "  gerrit:default_submit(X),\n"
                + "  X =.. [submit | Ls],\n"
                + "  add_non_author_approval(Ls, R),\n"
                + "  S =.. [submit | R].\n"
                + "\n"
                + "add_non_author_approval(S1, S2) :-\n"
                + "  gerrit:commit_author(A),\n"
                + "  gerrit:commit_label(label('Code-Review', 2), R),\n"
                + "  R \\= A, !,\n"
                + "  S2 = [label('Non-Author-Code-Review', ok(R)) | S1].\n"
                + "add_non_author_approval(S1,"
                + " [label('Non-Author-Code-Review', need(_)) | S1]).");
    push2.to(RefNames.REFS_CONFIG);
    testRepo.reset(oldHead);

    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    String heads = RefNames.REFS_HEADS + "*";

    // Allow user to approve
    try (ProjectConfigUpdate u = updateProject(project)) {
      Util.allow(
          u.getConfig(),
          Permission.forLabel(Util.codeReview().getName()),
          -2,
          2,
          registeredUsers,
          heads);
      u.save();
    }

    PushOneCommit.Result r = createChange();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ChangeStatus.MERGED);
    assertThat(change.labels.keySet()).containsExactly("Code-Review", "Non-Author-Code-Review");
    assertThat(change.permittedLabels.keySet()).containsExactly("Code-Review");
    assertPermitted(change, "Code-Review", 0, 1, 2);
  }

  @Test
  public void checkLabelsForAutoClosedChange() throws Exception {
    PushOneCommit.Result r = createChange();

    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result result = push.to("refs/heads/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ChangeStatus.MERGED);
    assertThat(change.labels.keySet()).containsExactly("Code-Review");
    assertPermitted(change, "Code-Review", 0, 1, 2);
  }

  @Test
  public void maxPermittedValueAllowed() throws Exception {
    final int minPermittedValue = -2;
    final int maxPermittedValue = +2;
    String heads = "refs/heads/*";

    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();

    gApi.changes().id(triplet).addReviewer(user.username());

    ChangeInfo c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    LabelInfo codeReview = c.labels.get("Code-Review");
    assertThat(codeReview.all).hasSize(1);
    ApprovalInfo approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.permittedVotingRange).isNotNull();
    // default values
    assertThat(approval.permittedVotingRange.min).isEqualTo(-1);
    assertThat(approval.permittedVotingRange.max).isEqualTo(1);

    try (ProjectConfigUpdate u = updateProject(project)) {
      Util.allow(
          u.getConfig(),
          Permission.forLabel("Code-Review"),
          minPermittedValue,
          maxPermittedValue,
          REGISTERED_USERS,
          heads);
      u.save();
    }

    c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    codeReview = c.labels.get("Code-Review");
    assertThat(codeReview.all).hasSize(1);
    approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.permittedVotingRange).isNotNull();
    assertThat(approval.permittedVotingRange.min).isEqualTo(minPermittedValue);
    assertThat(approval.permittedVotingRange.max).isEqualTo(maxPermittedValue);
  }

  @Test
  public void maxPermittedValueBlocked() throws Exception {
    try (ProjectConfigUpdate u = updateProject(project)) {
      Util.blockLabel(u.getConfig(), "Code-Review", REGISTERED_USERS, "refs/heads/*");
      u.save();
    }

    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();

    gApi.changes().id(triplet).addReviewer(user.username());

    ChangeInfo c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    LabelInfo codeReview = c.labels.get("Code-Review");
    assertThat(codeReview.all).hasSize(1);
    ApprovalInfo approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.permittedVotingRange).isNull();
  }

  @Test
  public void nonStrictLabelWithInvalidLabelPerDefault() throws Exception {
    String changeId = createChange().getChangeId();

    // Add a review with invalid labels.
    ReviewInput input = ReviewInput.approve().label("Code-Style", 1);
    gApi.changes().id(changeId).current().review(input);

    Map<String, Short> votes =
        gApi.changes().id(changeId).current().reviewer(admin.email()).votes();
    assertThat(votes.keySet()).containsExactly("Code-Review");
    assertThat(votes.values()).containsExactly((short) 2);
  }

  @Test
  public void nonStrictLabelWithInvalidValuePerDefault() throws Exception {
    String changeId = createChange().getChangeId();

    // Add a review with invalid label values.
    ReviewInput input = new ReviewInput().label("Code-Review", 3);
    gApi.changes().id(changeId).current().review(input);

    Map<String, Short> votes =
        gApi.changes().id(changeId).current().reviewer(admin.email()).votes();
    assertThat(votes).isEmpty();
  }

  @Test
  @GerritConfig(name = "change.strictLabels", value = "true")
  public void strictLabelWithInvalidLabel() throws Exception {
    String changeId = createChange().getChangeId();
    ReviewInput in = new ReviewInput().label("Code-Style", 1);

    exception.expect(BadRequestException.class);
    exception.expectMessage("label \"Code-Style\" is not a configured label");
    gApi.changes().id(changeId).current().review(in);
  }

  @Test
  @GerritConfig(name = "change.strictLabels", value = "true")
  public void strictLabelWithInvalidValue() throws Exception {
    String changeId = createChange().getChangeId();
    ReviewInput in = new ReviewInput().label("Code-Review", 3);

    exception.expect(BadRequestException.class);
    exception.expectMessage("label \"Code-Review\": 3 is not a valid value");
    gApi.changes().id(changeId).current().review(in);
  }

  @Test
  public void unresolvedCommentsBlocked() throws Exception {
    modifySubmitRules(
        "submit_rule(submit(R)) :- \n"
            + "gerrit:unresolved_comments_count(0), \n"
            + "!,"
            + "gerrit:uploader(U), \n"
            + "R = label('All-Comments-Resolved', ok(U)).\n"
            + "submit_rule(submit(R)) :- \n"
            + "gerrit:unresolved_comments_count(U), \n"
            + "U > 0,"
            + "R = label('All-Comments-Resolved', need(_)). \n\n");

    String oldHead = getRemoteHead().name();
    PushOneCommit.Result result1 =
        pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
    testRepo.reset(oldHead);
    PushOneCommit.Result result2 =
        pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");

    addComment(result1, "comment 1", true, false, null);
    addComment(result2, "comment 2", true, true, null);

    gApi.changes().id(result1.getChangeId()).current().submit();

    exception.expect(ResourceConflictException.class);
    exception.expectMessage("Failed to submit 1 change due to the following problems");
    exception.expectMessage("needs All-Comments-Resolved");
    gApi.changes().id(result2.getChangeId()).current().submit();
  }

  @Test
  public void pureRevertFactBlocksSubmissionOfNonReverts() throws Exception {
    addPureRevertSubmitRule();

    // Create a change that is not a revert of another change
    PushOneCommit.Result r1 = pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
    approve(r1.getChangeId());

    exception.expect(ResourceConflictException.class);
    exception.expectMessage("Failed to submit 1 change due to the following problems");
    exception.expectMessage("needs Is-Pure-Revert");
    gApi.changes().id(r1.getChangeId()).current().submit();
  }

  @Test
  public void pureRevertFactBlocksSubmissionOfNonPureReverts() throws Exception {
    PushOneCommit.Result r1 = pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
    merge(r1);

    addPureRevertSubmitRule();

    // Create a revert and push a content change
    String revertId = gApi.changes().id(r1.getChangeId()).revert().get().changeId;
    amendChange(revertId);
    approve(revertId);

    exception.expect(ResourceConflictException.class);
    exception.expectMessage("Failed to submit 1 change due to the following problems");
    exception.expectMessage("needs Is-Pure-Revert");
    gApi.changes().id(revertId).current().submit();
  }

  @Test
  public void pureRevertFactAllowsSubmissionOfPureReverts() throws Exception {
    // Create a change that we can later revert
    PushOneCommit.Result r1 = pushFactory.create(user.newIdent(), testRepo).to("refs/for/master");
    merge(r1);

    addPureRevertSubmitRule();

    // Create a revert and submit it
    String revertId = gApi.changes().id(r1.getChangeId()).revert().get().changeId;
    approve(revertId);
    gApi.changes().id(revertId).current().submit();
  }

  @Test
  public void changeCommitMessage() throws Exception {
    // Tests mutating the commit message as both the owner of the change and a regular user with
    // addPatchSet permission. Asserts that both cases succeed.
    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");

    for (com.google.gerrit.acceptance.TestAccount acc : ImmutableList.of(admin, user)) {
      requestScopeOperations.setApiUser(acc.id());
      String newMessage =
          "modified commit by " + acc.username() + "\n\nChange-Id: " + r.getChangeId() + "\n";
      gApi.changes().id(r.getChangeId()).setMessage(newMessage);
      RevisionApi rApi = gApi.changes().id(r.getChangeId()).current();
      assertThat(rApi.files().keySet()).containsExactly("/COMMIT_MSG", "a.txt");
      assertThat(getCommitMessage(r.getChangeId())).isEqualTo(newMessage);
      assertThat(rApi.description()).isEqualTo("Edit commit message");
    }

    // Verify tags, which should differ according to whether the change was WIP
    // at the time the commit message was edited. First, look at the last edit
    // we created above, when the change was not WIP.
    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(Iterables.getLast(info.messages).tag)
        .isEqualTo(ChangeMessagesUtil.TAG_UPLOADED_PATCH_SET);

    // Move the change to WIP and edit the commit message again, to observe a
    // different tag. Must switch to change owner to move into WIP.
    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(r.getChangeId()).setWorkInProgress();
    String newMessage = "modified commit in WIP change\n\nChange-Id: " + r.getChangeId() + "\n";
    gApi.changes().id(r.getChangeId()).setMessage(newMessage);
    info = gApi.changes().id(r.getChangeId()).get();
    assertThat(Iterables.getLast(info.messages).tag)
        .isEqualTo(ChangeMessagesUtil.TAG_UPLOADED_WIP_PATCH_SET);
  }

  @Test
  public void changeCommitMessageWithNoChangeIdSucceedsIfChangeIdNotRequired() throws Exception {
    ConfigInput configInput = new ConfigInput();
    configInput.requireChangeId = InheritableBoolean.FALSE;
    gApi.projects().name(project.get()).config(configInput);

    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");

    String newMessage = "modified commit\n";
    gApi.changes().id(r.getChangeId()).setMessage(newMessage);
    RevisionApi rApi = gApi.changes().id(r.getChangeId()).current();
    assertThat(rApi.files().keySet()).containsExactly("/COMMIT_MSG", "a.txt");
    assertThat(getCommitMessage(r.getChangeId())).isEqualTo(newMessage);
  }

  @Test
  public void changeCommitMessageWithNoChangeIdFails() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");
    exception.expect(ResourceConflictException.class);
    exception.expectMessage("missing Change-Id footer");
    gApi.changes().id(r.getChangeId()).setMessage("modified commit\n");
  }

  @Test
  public void changeCommitMessageNullNotAllowed() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");
    exception.expect(BadRequestException.class);
    exception.expectMessage("NUL character");
    gApi.changes()
        .id(r.getChangeId())
        .setMessage("test\0commit\n\nChange-Id: " + r.getChangeId() + "\n");
  }

  @Test
  public void changeCommitMessageWithWrongChangeIdFails() throws Exception {
    PushOneCommit.Result otherChange = createChange();
    PushOneCommit.Result r = createChange();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");
    exception.expect(ResourceConflictException.class);
    exception.expectMessage("wrong Change-Id footer");
    gApi.changes()
        .id(r.getChangeId())
        .setMessage("modified commit\n\nChange-Id: " + otherChange.getChangeId() + "\n");
  }

  @Test
  public void changeCommitMessageWithoutPermissionFails() throws Exception {
    // Create new project with clean permissions
    Project.NameKey p = projectOperations.newProject().create();
    TestRepository<InMemoryRepository> userTestRepo = cloneProject(p, user);
    // Block default permission
    block(p, "refs/for/*", Permission.ADD_PATCH_SET, REGISTERED_USERS);
    // Create change as user
    PushOneCommit push = pushFactory.create(user.newIdent(), userTestRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();
    // Try to change the commit message
    exception.expect(AuthException.class);
    exception.expectMessage("modifying commit message not permitted");
    gApi.changes().id(r.getChangeId()).setMessage("foo");
  }

  @Test
  public void changeCommitMessageWithSameMessageFails() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");
    exception.expect(ResourceConflictException.class);
    exception.expectMessage("new and existing commit message are the same");
    gApi.changes().id(r.getChangeId()).setMessage(getCommitMessage(r.getChangeId()));
  }

  @Test
  public void fourByteEmoji() throws Exception {
    // U+1F601 GRINNING FACE WITH SMILING EYES
    String smile = new String(Character.toChars(0x1f601));
    assertThat(smile).isEqualTo("😁");
    assertThat(smile).hasLength(2); // Thanks, Java.
    assertThat(smile.getBytes(UTF_8)).hasLength(4);

    String subject = "A happy change " + smile;
    PushOneCommit.Result r =
        pushFactory
            .create(admin.newIdent(), testRepo, subject, FILE_NAME, FILE_CONTENT)
            .to("refs/for/master");
    r.assertOkStatus();
    String id = r.getChangeId();

    ReviewInput ri = ReviewInput.approve();
    ri.message = "I like it " + smile;
    ReviewInput.CommentInput ci = new ReviewInput.CommentInput();
    ci.path = FILE_NAME;
    ci.side = Side.REVISION;
    ci.message = "Good " + smile;
    ri.comments = ImmutableMap.of(FILE_NAME, ImmutableList.of(ci));
    gApi.changes().id(id).current().review(ri);

    ChangeInfo info = gApi.changes().id(id).get(MESSAGES, CURRENT_COMMIT, CURRENT_REVISION);
    assertThat(info.subject).isEqualTo(subject);
    assertThat(Iterables.getLast(info.messages).message).endsWith(ri.message);
    assertThat(Iterables.getOnlyElement(info.revisions.values()).commit.message)
        .startsWith(subject);

    List<CommentInfo> comments =
        Iterables.getOnlyElement(gApi.changes().id(id).comments().values());
    assertThat(Iterables.getOnlyElement(comments).message).isEqualTo(ci.message);
  }

  @Test
  public void pureRevertReturnsTrueForPureRevert() throws Exception {
    PushOneCommit.Result r = createChange();
    merge(r);
    String revertId = gApi.changes().id(r.getChangeId()).revert().get().id;
    // Without query parameter
    assertThat(gApi.changes().id(revertId).pureRevert().isPureRevert).isTrue();
    // With query parameter
    assertThat(
            gApi.changes()
                .id(revertId)
                .pureRevert(getRemoteHead().toObjectId().name())
                .isPureRevert)
        .isTrue();
  }

  @Test
  public void pureRevertReturnsFalseOnContentChange() throws Exception {
    PushOneCommit.Result r1 = createChange();
    merge(r1);
    // Create a revert and expect pureRevert to be true
    String revertId = gApi.changes().id(r1.getChangeId()).revert().get().changeId;
    assertThat(gApi.changes().id(revertId).pureRevert().isPureRevert).isTrue();

    // Create a new PS and expect pureRevert to be false
    PushOneCommit.Result result = amendChange(revertId);
    result.assertOkStatus();
    assertThat(gApi.changes().id(revertId).pureRevert().isPureRevert).isFalse();
  }

  @Test
  public void pureRevertParameterTakesPrecedence() throws Exception {
    PushOneCommit.Result r1 = createChange("commit message", "a.txt", "content1");
    merge(r1);
    String oldHead = getRemoteHead().toObjectId().name();

    PushOneCommit.Result r2 = createChange("commit message", "a.txt", "content2");
    merge(r2);

    String revertId = gApi.changes().id(r2.getChangeId()).revert().get().changeId;
    assertThat(gApi.changes().id(revertId).pureRevert().isPureRevert).isTrue();
    assertThat(gApi.changes().id(revertId).pureRevert(oldHead).isPureRevert).isFalse();
  }

  @Test
  public void pureRevertReturnsFalseOnInvalidInput() throws Exception {
    PushOneCommit.Result r1 = createChange();
    merge(r1);

    exception.expect(BadRequestException.class);
    exception.expectMessage("invalid object ID");
    gApi.changes().id(createChange().getChangeId()).pureRevert("invalid id");
  }

  @Test
  public void pureRevertReturnsTrueWithCleanRebase() throws Exception {
    PushOneCommit.Result r1 = createChange("commit message", "a.txt", "content1");
    merge(r1);

    PushOneCommit.Result r2 = createChange("commit message", "b.txt", "content2");
    merge(r2);

    String revertId = gApi.changes().id(r1.getChangeId()).revert().get().changeId;
    // Rebase revert onto HEAD
    gApi.changes().id(revertId).rebase();
    // Check that pureRevert is true which implies that the commit can be rebased onto the original
    // commit.
    assertThat(gApi.changes().id(revertId).pureRevert().isPureRevert).isTrue();
  }

  @Test
  public void pureRevertReturnsFalseWithRebaseConflict() throws Exception {
    // Create an initial commit to serve as claimed original
    PushOneCommit.Result r1 = createChange("commit message", "a.txt", "content1");
    merge(r1);
    String claimedOriginal = getRemoteHead().toObjectId().name();

    // Change contents of the file to provoke a conflict
    merge(createChange("commit message", "a.txt", "content2"));

    // Create a commit that we can revert
    PushOneCommit.Result r2 = createChange("commit message", "a.txt", "content3");
    merge(r2);

    // Create a revert of r2
    String revertR3Id = gApi.changes().id(r2.getChangeId()).revert().id();
    // Assert that the change is a pure revert of it's 'revertOf'
    assertThat(gApi.changes().id(revertR3Id).pureRevert().isPureRevert).isTrue();
    // Assert that the change is not a pure revert of claimedOriginal because pureRevert is trying
    // to rebase this on claimed original, which fails.
    PureRevertInfo pureRevert = gApi.changes().id(revertR3Id).pureRevert(claimedOriginal);
    assertThat(pureRevert.isPureRevert).isFalse();
  }

  @Test
  public void pureRevertThrowsExceptionWhenChangeIsNotARevertAndNoIdProvided() throws Exception {
    exception.expect(BadRequestException.class);
    exception.expectMessage("revertOf not set");
    gApi.changes().id(createChange().getChangeId()).pureRevert();
  }

  @Test
  public void putTopicExceedLimitFails() throws Exception {
    String changeId = createChange().getChangeId();
    String topic = Stream.generate(() -> "t").limit(2049).collect(joining());

    exception.expect(BadRequestException.class);
    exception.expectMessage("topic length exceeds the limit");
    gApi.changes().id(changeId).topic(topic);
  }

  @Test
  public void submittableAfterLosingPermissions_MaxWithBlock() throws Exception {
    configLabel("Label", LabelFunction.MAX_WITH_BLOCK);
    submittableAfterLosingPermissions("Label");
  }

  @Test
  public void submittableAfterLosingPermissions_AnyWithBlock() throws Exception {
    configLabel("Label", LabelFunction.ANY_WITH_BLOCK);
    submittableAfterLosingPermissions("Label");
  }

  private void submittableAfterLosingPermissions(String label) throws Exception {
    String codeReviewLabel = "Code-Review";
    AccountGroup.UUID registered = SystemGroupBackend.REGISTERED_USERS;
    try (ProjectConfigUpdate u = updateProject(project)) {
      Util.allow(u.getConfig(), Permission.forLabel(label), -1, +1, registered, "refs/heads/*");
      Util.allow(
          u.getConfig(), Permission.forLabel(codeReviewLabel), -2, +2, registered, "refs/heads/*");
      u.save();
    }

    requestScopeOperations.setApiUser(user.id());
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    // Verify user's permitted range.
    ChangeInfo change = gApi.changes().id(changeId).get();
    assertPermitted(change, label, -1, 0, 1);
    assertPermitted(change, codeReviewLabel, -2, -1, 0, 1, 2);

    ReviewInput input = new ReviewInput();
    input.label(codeReviewLabel, 2);
    input.label(label, 1);
    gApi.changes().id(changeId).current().review(input);

    assertThat(gApi.changes().id(changeId).current().reviewer(user.email()).votes().keySet())
        .containsExactly(codeReviewLabel, label);
    assertThat(gApi.changes().id(changeId).current().reviewer(user.email()).votes().values())
        .containsExactly((short) 2, (short) 1);
    assertThat(gApi.changes().id(changeId).get().submittable).isTrue();

    requestScopeOperations.setApiUser(admin.id());
    // Remove user's permission for 'Label'.
    try (ProjectConfigUpdate u = updateProject(project)) {
      Util.remove(u.getConfig(), Permission.forLabel(label), registered, "refs/heads/*");
      // Update user's permitted range for 'Code-Review' to be -1...+1.
      Util.remove(u.getConfig(), Permission.forLabel(codeReviewLabel), registered, "refs/heads/*");
      Util.allow(
          u.getConfig(), Permission.forLabel(codeReviewLabel), -1, +1, registered, "refs/heads/*");
      u.save();
    }

    // Verify user's new permitted range.
    requestScopeOperations.setApiUser(user.id());
    change = gApi.changes().id(changeId).get();
    assertPermitted(change, label);
    assertPermitted(change, codeReviewLabel, -1, 0, 1);

    assertThat(gApi.changes().id(changeId).current().reviewer(user.email()).votes().values())
        .containsExactly((short) 2, (short) 1);
    assertThat(gApi.changes().id(changeId).get().submittable).isTrue();

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).current().submit();
  }

  @Test
  public void draftCommentsShouldNotUpdateChangeTimestamp() throws Exception {
    String changeId = createNewChange();
    Timestamp changeTs = getChangeLastUpdate(changeId);
    DraftApi draftApi = addDraftComment(changeId);
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
    draftApi.delete();
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
  }

  @Test
  public void deletingAllDraftCommentsShouldNotUpdateChangeTimestamp() throws Exception {
    String changeId = createNewChange();
    Timestamp changeTs = getChangeLastUpdate(changeId);
    addDraftComment(changeId);
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
    gApi.accounts().self().deleteDraftComments(new DeleteDraftCommentsInput());
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
  }

  private Timestamp getChangeLastUpdate(String changeId) throws RestApiException {
    Timestamp changeTs = gApi.changes().id(changeId).get().updated;
    return changeTs;
  }

  private String createNewChange() throws Exception {
    TestRepository<InMemoryRepository> userRepo = cloneProject(project, user);
    PushOneCommit.Result result =
        pushFactory.create(user.newIdent(), userRepo).to("refs/for/master");
    String changeId = result.getChangeId();
    return changeId;
  }

  private DraftApi addDraftComment(String changeId) throws RestApiException {
    DraftInput comment = new DraftInput();
    comment.message = "foo";
    comment.path = "/foo";
    return gApi.changes().id(changeId).current().createDraft(comment);
  }

  private String getCommitMessage(String changeId) throws RestApiException, IOException {
    return gApi.changes().id(changeId).current().file("/COMMIT_MSG").content().asString();
  }

  private void addComment(
      PushOneCommit.Result r,
      String message,
      boolean omitDuplicateComments,
      Boolean unresolved,
      String inReplyTo)
      throws Exception {
    ReviewInput.CommentInput c = new ReviewInput.CommentInput();
    c.line = 1;
    c.message = message;
    c.path = FILE_NAME;
    c.unresolved = unresolved;
    c.inReplyTo = inReplyTo;
    ReviewInput in = new ReviewInput();
    in.comments = new HashMap<>();
    in.comments.put(c.path, Lists.newArrayList(c));
    in.omitDuplicateComments = omitDuplicateComments;
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(in);
  }

  private static Iterable<Account.Id> getReviewers(Collection<AccountInfo> r) {
    if (r == null) {
      return ImmutableList.of();
    }
    return Iterables.transform(r, a -> new Account.Id(a._accountId));
  }

  private ChangeResource parseResource(PushOneCommit.Result r) throws Exception {
    return parseChangeResource(r.getChangeId());
  }

  private Optional<ReviewerState> getReviewerState(String changeId, Account.Id accountId)
      throws Exception {
    ChangeInfo c = gApi.changes().id(changeId).get(DETAILED_LABELS);
    Set<ReviewerState> states =
        c.reviewers.entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(a -> a._accountId == accountId.get()))
            .map(Map.Entry::getKey)
            .collect(toSet());
    assertThat(states.size()).named(states.toString()).isAtMost(1);
    return states.stream().findFirst();
  }

  private void setChangeStatus(Change.Id id, Change.Status newStatus) throws Exception {
    try (BatchUpdate batchUpdate =
        batchUpdateFactory.create(project, atrScope.get().getUser(), TimeUtil.nowTs())) {
      batchUpdate.addOp(id, new ChangeStatusUpdateOp(newStatus));
      batchUpdate.execute();
    }

    ChangeStatus changeStatus = gApi.changes().id(id.get()).get().status;
    assertThat(changeStatus).isEqualTo(newStatus.asChangeStatus());
  }

  private static class ChangeStatusUpdateOp implements BatchUpdateOp {
    private final Change.Status newStatus;

    ChangeStatusUpdateOp(Change.Status newStatus) {
      this.newStatus = newStatus;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws Exception {
      Change change = ctx.getChange();

      // Change status in database.
      change.setStatus(newStatus);

      // Change status in NoteDb.
      PatchSet.Id currentPatchSetId = change.currentPatchSetId();
      ctx.getUpdate(currentPatchSetId).setStatus(newStatus);

      return true;
    }
  }

  private void addPureRevertSubmitRule() throws Exception {
    modifySubmitRules(
        "submit_rule(submit(R)) :- \n"
            + "gerrit:pure_revert(1), \n"
            + "!,"
            + "gerrit:uploader(U), \n"
            + "R = label('Is-Pure-Revert', ok(U)).\n"
            + "submit_rule(submit(R)) :- \n"
            + "gerrit:pure_revert(U), \n"
            + "U \\= 1,"
            + "R = label('Is-Pure-Revert', need(_)). \n\n");
  }

  private void modifySubmitRules(String newContent) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      testRepo
          .branch(RefNames.REFS_CONFIG)
          .commit()
          .author(admin.newIdent())
          .committer(admin.newIdent())
          .add("rules.pl", newContent)
          .message("Modify rules.pl")
          .create();
    }
  }

  @Test
  @GerritConfig(name = "trackingid.jira-bug.footer", value = "Bug:")
  @GerritConfig(name = "trackingid.jira-bug.match", value = "JRA\\d{2,8}")
  @GerritConfig(name = "trackingid.jira-bug.system", value = "JIRA")
  public void trackingIds() throws Exception {
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT + "\n\n" + "Bug:JRA001",
            PushOneCommit.FILE_NAME,
            PushOneCommit.FILE_CONTENT);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(result.getChangeId()).get(TRACKING_IDS);
    Collection<TrackingIdInfo> trackingIds = change.trackingIds;
    assertThat(trackingIds).isNotNull();
    assertThat(trackingIds).hasSize(1);
    assertThat(trackingIds.iterator().next().system).isEqualTo("JIRA");
    assertThat(trackingIds.iterator().next().id).isEqualTo("JRA001");
  }

  @Test
  public void starUnstar() throws Exception {
    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();
    changeIndexedCounter.clear();

    gApi.accounts().self().starChange(triplet);
    ChangeInfo change = info(triplet);
    assertThat(change.starred).isTrue();
    assertThat(change.stars).contains(DEFAULT_LABEL);
    changeIndexedCounter.assertReindexOf(change);

    gApi.accounts().self().unstarChange(triplet);
    change = info(triplet);
    assertThat(change.starred).isNull();
    assertThat(change.stars).isNull();
    changeIndexedCounter.assertReindexOf(change);
  }

  @Test
  public void ignore() throws Exception {
    String email = "user2@example.com";
    String fullname = "User2";
    accountOperations
        .newAccount()
        .username("user2")
        .preferredEmail(email)
        .fullname(fullname)
        .create();

    PushOneCommit.Result r = createChange();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    in = new AddReviewerInput();
    in.reviewer = email;
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).ignore(true);
    assertThat(gApi.changes().id(r.getChangeId()).ignored()).isTrue();

    // New patch set notification is not sent to users ignoring the change
    sender.clear();
    requestScopeOperations.setApiUser(admin.id());
    amendChange(r.getChangeId());
    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Address address = new Address(fullname, email);
    assertThat(messages.get(0).rcpt()).containsExactly(address);

    // Review notification is not sent to users ignoring the change
    sender.clear();
    gApi.changes().id(r.getChangeId()).current().review(ReviewInput.approve());
    messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).rcpt()).containsExactly(address);

    // Abandoned notification is not sent to users ignoring the change
    sender.clear();
    gApi.changes().id(r.getChangeId()).abandon();
    messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).rcpt()).containsExactly(address);

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).ignore(false);
    assertThat(gApi.changes().id(r.getChangeId()).ignored()).isFalse();
  }

  @Test
  public void cannotIgnoreOwnChange() throws Exception {
    String changeId = createChange().getChangeId();

    exception.expect(BadRequestException.class);
    exception.expectMessage("cannot ignore own change");
    gApi.changes().id(changeId).ignore(true);
  }

  @Test
  public void cannotIgnoreStarredChange() throws Exception {
    String changeId = createChange().getChangeId();

    requestScopeOperations.setApiUser(user.id());
    gApi.accounts().self().starChange(changeId);
    assertThat(gApi.changes().id(changeId).get().starred).isTrue();

    exception.expect(ResourceConflictException.class);
    exception.expectMessage(
        "The labels "
            + StarredChangesUtil.DEFAULT_LABEL
            + " and "
            + StarredChangesUtil.IGNORE_LABEL
            + " are mutually exclusive. Only one of them can be set.");
    gApi.changes().id(changeId).ignore(true);
  }

  @Test
  public void cannotStarIgnoredChange() throws Exception {
    String changeId = createChange().getChangeId();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).ignore(true);
    assertThat(gApi.changes().id(changeId).ignored()).isTrue();

    exception.expect(ResourceConflictException.class);
    exception.expectMessage(
        "The labels "
            + StarredChangesUtil.DEFAULT_LABEL
            + " and "
            + StarredChangesUtil.IGNORE_LABEL
            + " are mutually exclusive. Only one of them can be set.");
    gApi.accounts().self().starChange(changeId);
  }

  @Test
  public void markAsReviewed() throws Exception {
    com.google.gerrit.acceptance.TestAccount user2 = accountCreator.user2();

    PushOneCommit.Result r = createChange();

    AddReviewerInput in = new AddReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    requestScopeOperations.setApiUser(user.id());
    assertThat(gApi.changes().id(r.getChangeId()).get().reviewed).isNull();
    gApi.changes().id(r.getChangeId()).markAsReviewed(true);
    assertThat(gApi.changes().id(r.getChangeId()).get().reviewed).isTrue();

    requestScopeOperations.setApiUser(user2.id());
    sender.clear();
    amendChange(r.getChangeId());

    requestScopeOperations.setApiUser(user.id());
    assertThat(gApi.changes().id(r.getChangeId()).get().reviewed).isNull();

    List<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).rcpt()).containsExactly(user.getEmailAddress());
  }

  @Test
  public void cannotSetUnreviewedLabelForPatchSetThatAlreadyHasReviewedLabel() throws Exception {
    String changeId = createChange().getChangeId();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).markAsReviewed(true);
    assertThat(gApi.changes().id(changeId).get().reviewed).isTrue();

    exception.expect(BadRequestException.class);
    exception.expectMessage(
        "The labels "
            + StarredChangesUtil.REVIEWED_LABEL
            + "/"
            + 1
            + " and "
            + StarredChangesUtil.UNREVIEWED_LABEL
            + "/"
            + 1
            + " are mutually exclusive. Only one of them can be set.");
    gApi.accounts()
        .self()
        .setStars(
            changeId, new StarsInput(ImmutableSet.of(StarredChangesUtil.UNREVIEWED_LABEL + "/1")));
  }

  @Test
  public void cannotSetReviewedLabelForPatchSetThatAlreadyHasUnreviewedLabel() throws Exception {
    String changeId = createChange().getChangeId();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).markAsReviewed(false);
    assertThat(gApi.changes().id(changeId).get().reviewed).isNull();

    exception.expect(BadRequestException.class);
    exception.expectMessage(
        "The labels "
            + StarredChangesUtil.REVIEWED_LABEL
            + "/"
            + 1
            + " and "
            + StarredChangesUtil.UNREVIEWED_LABEL
            + "/"
            + 1
            + " are mutually exclusive. Only one of them can be set.");
    gApi.accounts()
        .self()
        .setStars(
            changeId, new StarsInput(ImmutableSet.of(StarredChangesUtil.REVIEWED_LABEL + "/1")));
  }

  @Test
  public void setReviewedAndUnreviewedLabelsForDifferentPatchSets() throws Exception {
    String changeId = createChange().getChangeId();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).markAsReviewed(true);
    assertThat(gApi.changes().id(changeId).get().reviewed).isTrue();

    amendChange(changeId);
    assertThat(gApi.changes().id(changeId).get().reviewed).isNull();

    gApi.changes().id(changeId).markAsReviewed(false);
    assertThat(gApi.changes().id(changeId).get().reviewed).isNull();

    assertThat(gApi.accounts().self().getStars(changeId))
        .containsExactly(
            StarredChangesUtil.REVIEWED_LABEL + "/" + 1,
            StarredChangesUtil.UNREVIEWED_LABEL + "/" + 2);
  }

  @Test
  public void cannotSetInvalidLabel() throws Exception {
    String changeId = createChange().getChangeId();

    // label cannot contain whitespace
    String invalidLabel = "invalid label";
    exception.expect(BadRequestException.class);
    exception.expectMessage("invalid labels: " + invalidLabel);
    gApi.accounts().self().setStars(changeId, new StarsInput(ImmutableSet.of(invalidLabel)));
  }

  @Test
  public void changeDetailsDoesNotRequireIndex() throws Exception {
    // This set of options must be kept in sync with gr-rest-api-interface.js
    Set<ListChangesOption> options =
        ImmutableSet.of(
            ListChangesOption.ALL_COMMITS,
            ListChangesOption.ALL_REVISIONS,
            ListChangesOption.CHANGE_ACTIONS,
            ListChangesOption.CURRENT_ACTIONS,
            ListChangesOption.DETAILED_LABELS,
            ListChangesOption.DOWNLOAD_COMMANDS,
            ListChangesOption.MESSAGES,
            ListChangesOption.SUBMITTABLE,
            ListChangesOption.WEB_LINKS,
            ListChangesOption.SKIP_MERGEABLE);

    PushOneCommit.Result change = createChange();
    int number = gApi.changes().id(change.getChangeId()).get(options)._number;

    try (AutoCloseable ignored = disableChangeIndex()) {
      assertThat(gApi.changes().id(project.get(), number).get().changeId)
          .isEqualTo(change.getChangeId());
    }
  }

  private PushOneCommit.Result createWorkInProgressChange() throws Exception {
    return pushTo("refs/for/master%wip");
  }

  private BranchApi createBranch(String branch) throws Exception {
    return createBranch(new Branch.NameKey(project, branch));
  }

  private ThrowableSubject assertThatQueryException(String query) throws Exception {
    try {
      query(query);
    } catch (BadRequestException e) {
      return assertThat(e);
    }
    throw new AssertionError("expected BadRequestException");
  }
}
