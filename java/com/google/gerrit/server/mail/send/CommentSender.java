// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gerrit.server.mail.send;

import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.common.collect.Ordering;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.FilenameComparator;
import com.google.gerrit.exceptions.EmailException;
import com.google.gerrit.exceptions.NoSuchEntityException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.mail.MailHeader;
import com.google.gerrit.mail.MailProcessingUtil;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Comment;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RobotComment;
import com.google.gerrit.server.CommentsUtil;
import com.google.gerrit.server.account.ProjectWatches.NotifyType;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.mail.receive.Protocol;
import com.google.gerrit.server.patch.PatchFile;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.patch.PatchListObjectTooLargeException;
import com.google.gerrit.server.util.LabelVote;
import com.google.gwtorm.client.KeyUtil;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import org.apache.james.mime4j.dom.field.FieldName;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

/** Send comments, after the author of them hit used Publish Comments in the UI. */
public class CommentSender extends ReplyToChangeSender {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public interface Factory {
    CommentSender create(Project.NameKey project, Change.Id id);
  }

  private class FileCommentGroup {
    public String filename;
    public int patchSetId;
    public PatchFile fileData;
    public List<Comment> comments = new ArrayList<>();

    /** @return a web link to the given patch set and file. */
    public String getFileLink() {
      return args.urlFormatter
          .get()
          .getPatchFileView(change, patchSetId, KeyUtil.encode(filename))
          .orElse(null);
    }

    /** @return a web link to a comment within a given patch set and file. */
    public String getCommentLink(short side, int startLine) {
      return args.urlFormatter
          .get()
          .getInlineCommentView(change, patchSetId, KeyUtil.encode(filename), side, startLine)
          .orElse(null);
    }

    /**
     * @return A title for the group, i.e. "Commit Message", "Merge List", or "File [[filename]]".
     */
    public String getTitle() {
      if (Patch.COMMIT_MSG.equals(filename)) {
        return "Commit Message";
      } else if (Patch.MERGE_LIST.equals(filename)) {
        return "Merge List";
      } else {
        return "File " + filename;
      }
    }
  }

  private List<Comment> inlineComments = Collections.emptyList();
  private String patchSetComment;
  private List<LabelVote> labels = Collections.emptyList();
  private final CommentsUtil commentsUtil;
  private final boolean incomingEmailEnabled;
  private final String replyToAddress;

  @Inject
  public CommentSender(
      EmailArguments ea,
      CommentsUtil commentsUtil,
      @GerritServerConfig Config cfg,
      @Assisted Project.NameKey project,
      @Assisted Change.Id id) {
    super(ea, "comment", newChangeData(ea, project, id));
    this.commentsUtil = commentsUtil;
    this.incomingEmailEnabled =
        cfg.getEnum("receiveemail", null, "protocol", Protocol.NONE).ordinal()
            > Protocol.NONE.ordinal();
    this.replyToAddress = cfg.getString("sendemail", null, "replyToAddress");
  }

  public void setComments(List<Comment> comments) {
    inlineComments = comments;

    Set<String> paths = new HashSet<>();
    for (Comment c : comments) {
      if (!Patch.isMagic(c.key.filename)) {
        paths.add(c.key.filename);
      }
    }
    changeData.setCurrentFilePaths(Ordering.natural().sortedCopy(paths));
  }

  public void setPatchSetComment(String comment) {
    this.patchSetComment = comment;
  }

  public void setLabels(List<LabelVote> labels) {
    this.labels = labels;
  }

  @Override
  protected void init() throws EmailException {
    super.init();

    if (notify.handling().compareTo(NotifyHandling.OWNER_REVIEWERS) >= 0) {
      ccAllApprovals();
    }
    if (notify.handling().compareTo(NotifyHandling.ALL) >= 0) {
      bccStarredBy();
      includeWatchers(NotifyType.ALL_COMMENTS, !change.isWorkInProgress() && !change.isPrivate());
    }
    removeUsersThatIgnoredTheChange();

    // Add header that enables identifying comments on parsed email.
    // Grouping is currently done by timestamp.
    setHeader(MailHeader.COMMENT_DATE.fieldName(), timestamp);

    if (incomingEmailEnabled) {
      if (replyToAddress == null) {
        // Remove Reply-To and use outbound SMTP (default) instead.
        removeHeader(FieldName.REPLY_TO);
      } else {
        setHeader(FieldName.REPLY_TO, replyToAddress);
      }
    }
  }

  @Override
  public void formatChange() throws EmailException {
    appendText(textTemplate("Comment"));
    if (useHtml()) {
      appendHtml(soyHtmlTemplate("CommentHtml"));
    }
  }

  @Override
  public void formatFooter() throws EmailException {
    appendText(textTemplate("CommentFooter"));
    if (useHtml()) {
      appendHtml(soyHtmlTemplate("CommentFooterHtml"));
    }
  }

  /**
   * @return a list of FileCommentGroup objects representing the inline comments grouped by the
   *     file.
   */
  private List<CommentSender.FileCommentGroup> getGroupedInlineComments(Repository repo) {
    List<CommentSender.FileCommentGroup> groups = new ArrayList<>();

    // Loop over the comments and collect them into groups based on the file
    // location of the comment.
    FileCommentGroup currentGroup = null;
    for (Comment c : inlineComments) {
      // If it's a new group:
      if (currentGroup == null
          || !c.key.filename.equals(currentGroup.filename)
          || c.key.patchSetId != currentGroup.patchSetId) {
        currentGroup = new FileCommentGroup();
        currentGroup.filename = c.key.filename;
        currentGroup.patchSetId = c.key.patchSetId;
        // Get the patch list:
        PatchList patchList = null;
        try {
          patchList = getPatchList(c.key.patchSetId);
        } catch (PatchListObjectTooLargeException e) {
          logger.atWarning().log("Failed to get patch list: %s", e.getMessage());
        } catch (PatchListNotAvailableException e) {
          logger.atSevere().withCause(e).log("Failed to get patch list");
        }

        groups.add(currentGroup);
        if (patchList != null) {
          try {
            currentGroup.fileData = new PatchFile(repo, patchList, c.key.filename);
          } catch (IOException e) {
            logger.atWarning().withCause(e).log(
                "Cannot load %s from %s in %s",
                c.key.filename, patchList.getNewId().name(), projectState.getName());
            currentGroup.fileData = null;
          }
        }
      }

      if (currentGroup.fileData != null) {
        currentGroup.comments.add(c);
      }
    }

    groups.sort(Comparator.comparing(g -> g.filename, FilenameComparator.INSTANCE));
    return groups;
  }

  /** Get the set of accounts whose comments have been replied to in this email. */
  private HashSet<Account.Id> getReplyAccounts() {
    HashSet<Account.Id> replyAccounts = new HashSet<>();

    // Track visited parent UUIDs to avoid cycles.
    HashSet<String> visitedUuids = new HashSet<>();

    for (Comment comment : inlineComments) {
      visitedUuids.add(comment.key.uuid);

      // Traverse the parent relation to the top of the comment thread.
      Comment current = comment;
      while (current.parentUuid != null && !visitedUuids.contains(current.parentUuid)) {
        Optional<Comment> optParent = getParent(current);
        if (!optParent.isPresent()) {
          // There is a parent UUID, but it cannot be loaded, break from the comment thread.
          break;
        }

        Comment parent = optParent.get();
        replyAccounts.add(parent.author.getId());
        visitedUuids.add(current.parentUuid);
        current = parent;
      }
    }
    return replyAccounts;
  }

  private String getCommentLinePrefix(Comment comment) {
    int lineNbr = comment.range == null ? comment.lineNbr : comment.range.startLine;
    StringBuilder sb = new StringBuilder();
    sb.append("PS").append(comment.key.patchSetId);
    if (lineNbr != 0) {
      sb.append(", Line ").append(lineNbr);
    }
    sb.append(": ");
    return sb.toString();
  }

  /**
   * @return the lines of file content in fileData that are encompassed by range on the given side.
   */
  private List<String> getLinesByRange(Comment.Range range, PatchFile fileData, short side) {
    List<String> lines = new ArrayList<>();

    for (int n = range.startLine; n <= range.endLine; n++) {
      String s = getLine(fileData, side, n);
      if (n == range.startLine && n == range.endLine && range.startChar < range.endChar) {
        s = s.substring(Math.min(range.startChar, s.length()), Math.min(range.endChar, s.length()));
      } else if (n == range.startLine) {
        s = s.substring(Math.min(range.startChar, s.length()));
      } else if (n == range.endLine) {
        s = s.substring(0, Math.min(range.endChar, s.length()));
      }
      lines.add(s);
    }
    return lines;
  }

  /**
   * Get the parent comment of a given comment.
   *
   * @param child the comment with a potential parent comment.
   * @return an optional comment that will be present if the given comment has a parent, and is
   *     empty if it does not.
   */
  private Optional<Comment> getParent(Comment child) {
    if (child.parentUuid == null) {
      return Optional.empty();
    }

    Comment.Key key = new Comment.Key(child.parentUuid, child.key.filename, child.key.patchSetId);
    try {
      return commentsUtil.getPublished(changeData.notes(), key);
    } catch (StorageException e) {
      logger.atWarning().log("Could not find the parent of this comment: %s", child);
      return Optional.empty();
    }
  }

  /**
   * Retrieve the file lines referred to by a comment.
   *
   * @param comment The comment that refers to some file contents. The comment may be a line comment
   *     or a ranged comment.
   * @param fileData The file on which the comment appears.
   * @return file contents referred to by the comment. If the comment is a line comment, the result
   *     will be a list of one string. Otherwise it will be a list of one or more strings.
   */
  private List<String> getLinesOfComment(Comment comment, PatchFile fileData) {
    List<String> lines = new ArrayList<>();
    if (comment.lineNbr == 0) {
      // file level comment has no line
      return lines;
    }
    if (comment.range == null) {
      lines.add(getLine(fileData, comment.side, comment.lineNbr));
    } else {
      lines.addAll(getLinesByRange(comment.range, fileData, comment.side));
    }
    return lines;
  }

  /**
   * Return true if `line` looks interesting to have at the top of a comment context in C.
   *
   * @param line The line.
   */
  private boolean isHeaderLineC(String line) {
    if (Character.isWhitespace(line.charAt(0))) {
      return false;
    }

    if (line.equals("{")) {
      return false;
    }

    return true;
  }

  // Number of lines above and below the line(s) of interest.
  static final int NUM_CONTEXT_LINES = 5;

  // Return true if we should keep reading lines upwards.
  private boolean contextKeepGoing(int lineNo, int minPrintedLine, boolean seenHeaderLine) {
    // If we're at the top of the file, stop.
    if (lineNo == 0) {
      return false;
    }

    // If we still haven't seen something that looks like a good header,
    // we keep going.
    if (!seenHeaderLine) {
      return true;
    }

    // If we haven't printed the minimum lines we want yet, we keep going.
    if (lineNo >= minPrintedLine) {
      return true;
    }

    return false;
  }

  private List<String> getLinesOfCommentWithContext(Comment comment, PatchFile fileData) {
    List<String> lines = new ArrayList<>();
    if (comment.lineNbr == 0) {
      // A file level comment has no line.
      return lines;
    }

    boolean seenHeaderLine = false;
    boolean printedEllipsis = false;

    // Both are inclusive.  The doc in Comment.Range says end is exclusive, by
    // my experience is that it's inclusive..
    int commentRangeBeginLine = comment.range != null ? comment.range.startLine : comment.lineNbr;
    int commentRangeEndLine = comment.range != null ? comment.range.endLine : comment.lineNbr;

    // This is the lower bound of the lines we'll print.
    final int minPrintedLine = Math.max(commentRangeBeginLine - NUM_CONTEXT_LINES, 0);

    int lineNo = commentRangeEndLine + NUM_CONTEXT_LINES;

    // Try to clamp commentRangeEndLine to the number of lines in the file.
    try {
      int numLinesInFile = fileData.getNumLines(comment.side);

      lineNo = Math.min(numLinesInFile, lineNo);
    } catch (Exception e) {
      // Too bad, lineNo will stay the same.  If it's past the end of the file,
      // we'll just get extra empty lines, since that's what getLine returns for
      // non-existent lines.
    }

    // Largest width we'll need to format line numbers.
    int lineNoWidth = Integer.toString(lineNo).length();

    // The general idea is: process the range we want to print (comment range
    // +/- N lines) backwards .  If, in those 10 lines, we have not seen
    // something that constitutes a good header, keep looking at lines backwards
    // (but don't print them) until we find an interesting header line, and
    // print it.  Print an ellipsis if there are omitted lines between the
    // header and the rest of the context.  A final result could look like this
    // (the comment has been put on line 148, hence the arrow):
    //
    // 105 | void bt_self_component_port_input_message_iterator_destroy(struct bt_object *obj)
    //     | ...
    // 143 |
    // 144 | 		g_queue_free(iterator->auto_seek.msgs);
    // 145 | 		iterator->auto_seek.msgs = NULL;
    // 146 | 	}
    // 147 |
    // 148 > 	if (iterator->upstream_msg_iters) {
    // 149 | 		/*
    // 150 | 		 * At this point the message iterator is finalized, so
    // 151 | 		 * it's detached from any upstream message iterator.
    // 152 | 		 */
    // 153 | 		BT_ASSERT(iterator->upstream_msg_iters->len == 0);

    while (contextKeepGoing(lineNo, minPrintedLine, seenHeaderLine)) {
      String line = getLine(fileData, comment.side, lineNo);

      // Only consider it a suitable header if it's in or above the comment
      // range.  For example, if there's a function declaration below in the
      // context, it doesn't count as a suitable header for the comment context.
      boolean isHeaderLine =
          lineNo <= commentRangeEndLine && line.length() > 0 && isHeaderLineC(line);

      // If it's in the range of context we want to print or it's the header,
      // print it.
      if (lineNo >= minPrintedLine || isHeaderLine) {

        char separator =
            lineNo >= commentRangeBeginLine && lineNo <= commentRangeEndLine ? '>' : '|';
        String lineWithNo = String.format("%" + lineNoWidth + "d %c %s", lineNo, separator, line);
        lines.add(lineWithNo);
      } else if (!printedEllipsis) {
        // First extra line (on top of the minimal amount of context we want
        // to print) and we know there will be more, insert an ellipsis.
        String ellipsisLine = String.format("%" + lineNoWidth + "s | ...", "");
        lines.add(ellipsisLine);

        printedEllipsis = true;
      }

      seenHeaderLine |= isHeaderLine;

      lineNo--;
    }

    Collections.reverse(lines);

    return lines;
  }

  /**
   * @return a shortened version of the given comment's message. Will be shortened to 100 characters
   *     or the first line, or following the last period within the first 100 characters, whichever
   *     is shorter. If the message is shortened, an ellipsis is appended.
   */
  protected static String getShortenedCommentMessage(String message) {
    int threshold = 100;
    String fullMessage = message.trim();
    String msg = fullMessage;

    if (msg.length() > threshold) {
      msg = msg.substring(0, threshold);
    }

    int lf = msg.indexOf('\n');
    int period = msg.lastIndexOf('.');

    if (lf > 0) {
      // Truncate if a line feed appears within the threshold.
      msg = msg.substring(0, lf);

    } else if (period > 0) {
      // Otherwise truncate if there is a period within the threshold.
      msg = msg.substring(0, period + 1);
    }

    // Append an ellipsis if the message has been truncated.
    if (!msg.equals(fullMessage)) {
      msg += " […]";
    }

    return msg;
  }

  protected static String getShortenedCommentMessage(Comment comment) {
    return getShortenedCommentMessage(comment.message);
  }

  /**
   * @return grouped inline comment data mapped to data structures that are suitable for passing
   *     into Soy.
   */
  private List<Map<String, Object>> getCommentGroupsTemplateData(Repository repo) {
    List<Map<String, Object>> commentGroups = new ArrayList<>();

    for (CommentSender.FileCommentGroup group : getGroupedInlineComments(repo)) {
      Map<String, Object> groupData = new HashMap<>();
      groupData.put("link", group.getFileLink());
      groupData.put("title", group.getTitle());
      groupData.put("patchSetId", group.patchSetId);

      List<Map<String, Object>> commentsList = new ArrayList<>();
      for (Comment comment : group.comments) {
        Map<String, Object> commentData = new HashMap<>();
        commentData.put("lines", getLinesOfComment(comment, group.fileData));
        commentData.put("linesWithContext", getLinesOfCommentWithContext(comment, group.fileData));
        commentData.put("message", comment.message.trim());
        List<CommentFormatter.Block> blocks = CommentFormatter.parse(comment.message);
        commentData.put("messageBlocks", commentBlocksToSoyData(blocks));

        // Set the prefix.
        String prefix = getCommentLinePrefix(comment);
        commentData.put("linePrefix", prefix);
        commentData.put("linePrefixEmpty", Strings.padStart(": ", prefix.length(), ' '));

        // Set line numbers.
        int startLine;
        if (comment.range == null) {
          startLine = comment.lineNbr;
        } else {
          startLine = comment.range.startLine;
          commentData.put("endLine", comment.range.endLine);
        }
        commentData.put("startLine", startLine);

        // Set the comment link.
        if (comment.lineNbr == 0) {
          commentData.put("link", group.getFileLink());
        } else {
          commentData.put("link", group.getCommentLink(comment.side, startLine));
        }

        // Set robot comment data.
        if (comment instanceof RobotComment) {
          RobotComment robotComment = (RobotComment) comment;
          commentData.put("isRobotComment", true);
          commentData.put("robotId", robotComment.robotId);
          commentData.put("robotRunId", robotComment.robotRunId);
          commentData.put("robotUrl", robotComment.url);
        } else {
          commentData.put("isRobotComment", false);
        }

        // If the comment has a quote, don't bother loading the parent message.
        if (!hasQuote(blocks)) {
          // Set parent comment info.
          Optional<Comment> parent = getParent(comment);
          if (parent.isPresent()) {
            commentData.put("parentMessage", getShortenedCommentMessage(parent.get()));
          }
        }

        commentsList.add(commentData);
      }
      groupData.put("comments", commentsList);

      commentGroups.add(groupData);
    }
    return commentGroups;
  }

  private List<Map<String, Object>> commentBlocksToSoyData(List<CommentFormatter.Block> blocks) {
    return blocks.stream()
        .map(
            b -> {
              Map<String, Object> map = new HashMap<>();
              switch (b.type) {
                case PARAGRAPH:
                  map.put("type", "paragraph");
                  map.put("text", b.text);
                  break;
                case PRE_FORMATTED:
                  map.put("type", "pre");
                  map.put("text", b.text);
                  break;
                case QUOTE:
                  map.put("type", "quote");
                  map.put("quotedBlocks", commentBlocksToSoyData(b.quotedBlocks));
                  break;
                case LIST:
                  map.put("type", "list");
                  map.put("items", b.items);
                  break;
              }
              return map;
            })
        .collect(toList());
  }

  private boolean hasQuote(List<CommentFormatter.Block> blocks) {
    for (CommentFormatter.Block block : blocks) {
      if (block.type == CommentFormatter.BlockType.QUOTE) {
        return true;
      }
    }
    return false;
  }

  private Repository getRepository() {
    try {
      return args.server.openRepository(projectState.getNameKey());
    } catch (IOException e) {
      return null;
    }
  }

  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  @Inject private SitePaths paths;

  private String readAllInputStream(InputStream stream) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
    StringBuilder sb = new StringBuilder();

    while (true) {
      char[] buf = new char[4096];
      int n = reader.read(buf);

      if (n < 0) {
        break;
      }

      sb.append(buf, 0, n);
    }

    return sb.toString();
  }

  private String getDiffWithComments() {
    // Find out path to our super script that generates diff interleaved with
    // comments.
    Path scriptPath = this.paths.generate_comment_diff_py;
    String webUrl = this.args.canonicalWebUrlProvider.get();

    if (webUrl == null) {
      logger.atInfo().log("canonicalWebUrl is not set, can't compute diff with comments.");
      return "";
    }

    // The "key" we pass to our script to identify which comments are included
    // in this message are the author id and the timestamp.  The timestamp is
    // in the same format as the REST API returns, e.g.:
    //
    //   2019-11-06 19:47:23.000000000
    //
    // The nanoseconds part returned by the REST API is always all zeroes,
    // unlike the timestamp in this class.  So create a copy, to which we
    // assign zeroes.
    //
    // This code has been copied from
    // java/com/google/gerrit/json/SqlTimestampDeserializer.java
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    f.setTimeZone(UTC);
    f.setLenient(true);
    Timestamp timestampWithZeroes = (Timestamp) timestamp.clone();
    timestampWithZeroes.setNanos(0);
    String timestampStr = f.format(timestampWithZeroes) + "000000";

    String[] args = {
      scriptPath.toString(),
      webUrl,
      this.change.getId().toString(),
      this.fromId.toString(),
      timestampStr,
    };

    logger.atInfo().log("Running: %s", String.join(" ", args));
    ProcessBuilder pb = new ProcessBuilder(args);

    try {
      Process start = pb.start();

      // Consume all stdout and stderr.
      InputStream stdout = start.getInputStream();
      String out = this.readAllInputStream(stdout);

      InputStream stderr = start.getErrorStream();
      String err = this.readAllInputStream(stderr);

      // Obtain the exit status of the process.
      int status = -1;

      while (status == -1) {
        try {
          status = start.waitFor();
        } catch (InterruptedException e) {
          // Nothing, we just retry.
        }
      }

      if (!err.isEmpty()) {
        logger.atSevere().log("stderr from generate-comment-diff.py: %s", err);
      }

      logger.atInfo().log("generate-comment-diff.py exited with status: %d", status);
      return out;
    } catch (IOException e) {
      logger.atSevere().log("Error running generate-comment-diff.py: %s", e.toString());
      return "";
    }
  }

  @Override
  protected void setupSoyContext() {
    super.setupSoyContext();
    boolean hasComments;
    try (Repository repo = getRepository()) {
      List<Map<String, Object>> files = getCommentGroupsTemplateData(repo);
      soyContext.put("commentFiles", files);
      hasComments = !files.isEmpty();
    }

    soyContext.put("diffWithComments", this.getDiffWithComments());
    soyContext.put(
        "patchSetCommentBlocks", commentBlocksToSoyData(CommentFormatter.parse(patchSetComment)));
    soyContext.put("labels", getLabelVoteSoyData(labels));
    soyContext.put("commentCount", inlineComments.size());
    soyContext.put("commentTimestamp", getCommentTimestamp());
    soyContext.put(
        "coverLetterBlocks", commentBlocksToSoyData(CommentFormatter.parse(getCoverLetter())));

    footers.add(MailHeader.COMMENT_DATE.withDelimiter() + getCommentTimestamp());
    footers.add(MailHeader.HAS_COMMENTS.withDelimiter() + (hasComments ? "Yes" : "No"));
    footers.add(MailHeader.HAS_LABELS.withDelimiter() + (labels.isEmpty() ? "No" : "Yes"));

    for (Account.Id account : getReplyAccounts()) {
      footers.add(MailHeader.COMMENT_IN_REPLY_TO.withDelimiter() + getNameEmailFor(account));
    }
  }

  private String getLine(PatchFile fileInfo, short side, int lineNbr) {
    try {
      return fileInfo.getLine(side, lineNbr);
    } catch (IOException err) {
      // Default to the empty string if the file cannot be safely read.
      logger.atWarning().withCause(err).log("Failed to read file on side %d", side);
      return "";
    } catch (IndexOutOfBoundsException err) {
      // Default to the empty string if the given line number does not appear
      // in the file.
      logger.atFine().withCause(err).log(
          "Failed to get line number %d of file on side %d", lineNbr, side);
      return "";
    } catch (NoSuchEntityException err) {
      // Default to the empty string if the side cannot be found.
      logger.atWarning().withCause(err).log("Side %d of file didn't exist", side);
      return "";
    }
  }

  private List<Map<String, Object>> getLabelVoteSoyData(List<LabelVote> votes) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (LabelVote vote : votes) {
      Map<String, Object> data = new HashMap<>();
      data.put("label", vote.label());

      // Soy needs the short to be cast as an int for it to get converted to the
      // correct tamplate type.
      data.put("value", (int) vote.value());
      result.add(data);
    }
    return result;
  }

  private String getCommentTimestamp() {
    // Grouping is currently done by timestamp.
    return MailProcessingUtil.rfcDateformatter.format(
        ZonedDateTime.ofInstant(timestamp.toInstant(), ZoneId.of("UTC")));
  }

  @Override
  protected boolean supportsHtml() {
    return true;
  }
}
