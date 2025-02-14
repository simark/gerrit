/**
 * @license
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
(function() {
  'use strict';

  const MSG_EMPTY_BLAME = 'No blame information for this diff.';

  const EVENT_AGAINST_PARENT = 'diff-against-parent';
  const EVENT_ZERO_REBASE = 'rebase-percent-zero';
  const EVENT_NONZERO_REBASE = 'rebase-percent-nonzero';

  const DiffViewMode = {
    SIDE_BY_SIDE: 'SIDE_BY_SIDE',
    UNIFIED: 'UNIFIED_DIFF',
  };

  /** @enum {string} */
  const TimingLabel = {
    TOTAL: 'Diff Total Render',
    CONTENT: 'Diff Content Render',
    SYNTAX: 'Diff Syntax Render',
  };

  const WHITESPACE_IGNORE_NONE = 'IGNORE_NONE';

  /**
   * @param {Object} diff
   * @return {boolean}
   */
  function isImageDiff(diff) {
    if (!diff) { return false; }

    const isA = diff.meta_a &&
        diff.meta_a.content_type.startsWith('image/');
    const isB = diff.meta_b &&
        diff.meta_b.content_type.startsWith('image/');

    return !!(diff.binary && (isA || isB));
  }

  /** @enum {string} */
  Gerrit.DiffSide = {
    LEFT: 'left',
    RIGHT: 'right',
  };

  /**
   * Wrapper around gr-diff.
   *
   * Webcomponent fetching diffs and related data from restAPI and passing them
   * to the presentational gr-diff for rendering.
   */
  Polymer({
    is: 'gr-diff-host',
    _legacyUndefinedCheck: true,

    /**
     * Fired when the user selects a line.
     * @event line-selected
     */

    /**
     * Fired if being logged in is required.
     *
     * @event show-auth-required
     */

    /**
     * Fired when a comment is saved or discarded
     *
     * @event diff-comments-modified
     */

    properties: {
      changeNum: String,
      noAutoRender: {
        type: Boolean,
        value: false,
      },
      /** @type {?} */
      patchRange: Object,
      path: String,
      prefs: {
        type: Object,
      },
      projectName: String,
      displayLine: {
        type: Boolean,
        value: false,
      },
      isImageDiff: {
        type: Boolean,
        computed: '_computeIsImageDiff(diff)',
        notify: true,
      },
      commitRange: Object,
      filesWeblinks: {
        type: Object,
        value() { return {}; },
        notify: true,
      },
      hidden: {
        type: Boolean,
        reflectToAttribute: true,
      },
      noRenderOnPrefsChange: {
        type: Boolean,
        value: false,
      },
      comments: {
        type: Object,
        observer: '_commentsChanged',
      },
      lineWrapping: {
        type: Boolean,
        value: false,
      },
      viewMode: {
        type: String,
        value: DiffViewMode.SIDE_BY_SIDE,
      },

      /**
       * Special line number which should not be collapsed into a shared region.
       * @type {{
       *  number: number,
       *  leftSide: {boolean}
       * }|null}
       */
      lineOfInterest: Object,

      /**
       * If the diff fails to load, show the failure message in the diff rather
       * than bubbling the error up to the whole page. This is useful for when
       * loading inline diffs because one diff failing need not mark the whole
       * page with a failure.
       */
      showLoadFailure: Boolean,

      isBlameLoaded: {
        type: Boolean,
        notify: true,
        computed: '_computeIsBlameLoaded(_blame)',
      },

      _loggedIn: {
        type: Boolean,
        value: false,
      },

      _loading: {
        type: Boolean,
        value: false,
      },

      /** @type {?string} */
      _errorMessage: {
        type: String,
        value: null,
      },

      /** @type {?Object} */
      _baseImage: Object,
      /** @type {?Object} */
      _revisionImage: Object,
      /**
       * This is a DiffInfo object.
       */
      diff: {
        type: Object,
        notify: true,
      },

      /** @type {?Object} */
      _blame: {
        type: Object,
        value: null,
      },

      _loadedWhitespaceLevel: String,

      _parentIndex: {
        type: Number,
        computed: '_computeParentIndex(patchRange.*)',
      },
    },

    behaviors: [
      Gerrit.PatchSetBehavior,
    ],

    listeners: {
      // These are named inconsistently for a reason:
      // The create-comment event is fired to indicate that we should
      // create a comment.
      // The comment-* events are just notifying that the comments did already
      // change in some way, and that we should update any models we may want
      // to keep in sync.
      'create-comment': '_handleCreateComment',
      'comment-discard': '_handleCommentDiscard',
      'comment-update': '_handleCommentUpdate',
      'comment-save': '_handleCommentSave',

      'render-start': '_handleRenderStart',
      'render-content': '_handleRenderContent',
      'render-syntax': '_handleRenderSyntax',

      'normalize-range': '_handleNormalizeRange',
    },

    observers: [
      '_whitespaceChanged(prefs.ignore_whitespace, _loadedWhitespaceLevel,' +
          ' noRenderOnPrefsChange)',
    ],

    ready() {
      if (this._canReload()) {
        this.reload();
      }
    },

    attached() {
      this._getLoggedIn().then(loggedIn => {
        this._loggedIn = loggedIn;
      });
    },

    /** @return {!Promise} */
    reload() {
      this._loading = true;
      this._errorMessage = null;
      const whitespaceLevel = this._getIgnoreWhitespace();

      const diffRequest = this._getDiff()
          .then(diff => {
            this._loadedWhitespaceLevel = whitespaceLevel;
            this._reportDiff(diff);
            if (this._getIgnoreWhitespace() !== WHITESPACE_IGNORE_NONE) {
              return this._translateChunksToIgnore(diff);
            }
            return diff;
          })
          .catch(e => {
            this._handleGetDiffError(e);
            return null;
          });

      const assetRequest = diffRequest.then(diff => {
        // If the diff is null, then it's failed to load.
        if (!diff) { return null; }

        return this._loadDiffAssets(diff);
      });

      return Promise.all([diffRequest, assetRequest])
          .then(results => {
            const diff = results[0];
            if (!diff) {
              return Promise.resolve();
            }
            this.filesWeblinks = this._getFilesWeblinks(diff);
            return new Promise(resolve => {
              const callback = () => {
                resolve();
                this.removeEventListener('render', callback);
              };
              this.addEventListener('render', callback);
              this.diff = diff;
            });
          })
          .catch(err => {
            console.warn('Error encountered loading diff:', err);
          })
          .then(() => { this._loading = false; });
    },

    _getFilesWeblinks(diff) {
      if (!this.commitRange) { return {}; }
      return {
        meta_a: Gerrit.Nav.getFileWebLinks(
            this.projectName, this.commitRange.baseCommit, this.path,
            {weblinks: diff && diff.meta_a && diff.meta_a.web_links}),
        meta_b: Gerrit.Nav.getFileWebLinks(
            this.projectName, this.commitRange.commit, this.path,
            {weblinks: diff && diff.meta_b && diff.meta_b.web_links}),
      };
    },

    /** Cancel any remaining diff builder rendering work. */
    cancel() {
      this.$.diff.cancel();
    },

    /** @return {!Array<!HTMLElement>} */
    getCursorStops() {
      return this.$.diff.getCursorStops();
    },

    /** @return {boolean} */
    isRangeSelected() {
      return this.$.diff.isRangeSelected();
    },

    toggleLeftDiff() {
      this.$.diff.toggleLeftDiff();
    },

    /**
     * Load and display blame information for the base of the diff.
     * @return {Promise} A promise that resolves when blame finishes rendering.
     */
    loadBlame() {
      return this.$.restAPI.getBlame(this.changeNum, this.patchRange.patchNum,
          this.path, true)
          .then(blame => {
            if (!blame.length) {
              this.fire('show-alert', {message: MSG_EMPTY_BLAME});
              return Promise.reject(MSG_EMPTY_BLAME);
            }

            this._blame = blame;
          });
    },

    /** Unload blame information for the diff. */
    clearBlame() {
      this._blame = null;
    },

    /**
     * The thread elements in this diff, in no particular order.
     * @return {!Array<!HTMLElement>}
     */
    getThreadEls() {
      // Polymer2: querySelectorAll returns NodeList instead of Array.
      return Array.from(
          Polymer.dom(this.$.diff).querySelectorAll('.comment-thread'));
    },

    /** @param {HTMLElement} el */
    addDraftAtLine(el) {
      this.$.diff.addDraftAtLine(el);
    },

    clearDiffContent() {
      this.$.diff.clearDiffContent();
    },

    expandAllContext() {
      this.$.diff.expandAllContext();
    },

    /** @return {!Promise} */
    _getLoggedIn() {
      return this.$.restAPI.getLoggedIn();
    },

    /** @return {boolean}} */
    _canReload() {
      return !!this.changeNum && !!this.patchRange && !!this.path &&
          !this.noAutoRender;
    },

    /** @return {!Promise<!Object>} */
    _getDiff() {
      // Wrap the diff request in a new promise so that the error handler
      // rejects the promise, allowing the error to be handled in the .catch.
      return new Promise((resolve, reject) => {
        this.$.restAPI.getDiff(
            this.changeNum,
            this.patchRange.basePatchNum,
            this.patchRange.patchNum,
            this.path,
            this._getIgnoreWhitespace(),
            reject)
            .then(resolve);
      });
    },

    _handleGetDiffError(response) {
      // Loading the diff may respond with 409 if the file is too large. In this
      // case, use a toast error..
      if (response.status === 409) {
        this.fire('server-error', {response});
        return;
      }

      if (this.showLoadFailure) {
        this._errorMessage = [
          'Encountered error when loading the diff:',
          response.status,
          response.statusText,
        ].join(' ');
        return;
      }

      this.fire('page-error', {response});
    },

    /**
     * Report info about the diff response.
     */
    _reportDiff(diff) {
      if (!diff || !diff.content) { return; }

      // Count the delta lines stemming from normal deltas, and from
      // due_to_rebase deltas.
      let nonRebaseDelta = 0;
      let rebaseDelta = 0;
      diff.content.forEach(chunk => {
        if (chunk.ab) { return; }
        const deltaSize = Math.max(
            chunk.a ? chunk.a.length : 0, chunk.b ? chunk.b.length : 0);
        if (chunk.due_to_rebase) {
          rebaseDelta += deltaSize;
        } else {
          nonRebaseDelta += deltaSize;
        }
      });

      // Find the percent of the delta from due_to_rebase chunks rounded to two
      // digits. Diffs with no delta are considered 0%.
      const totalDelta = rebaseDelta + nonRebaseDelta;
      const percentRebaseDelta = !totalDelta ? 0 :
          Math.round(100 * rebaseDelta / totalDelta);

      // Report the due_to_rebase percentage in the "diff" category when
      // applicable.
      if (this.patchRange.basePatchNum === 'PARENT') {
        this.$.reporting.reportInteraction(EVENT_AGAINST_PARENT);
      } else if (percentRebaseDelta === 0) {
        this.$.reporting.reportInteraction(EVENT_ZERO_REBASE);
      } else {
        this.$.reporting.reportInteraction(EVENT_NONZERO_REBASE,
            percentRebaseDelta);
      }
    },

    /**
     * @param {Object} diff
     * @return {!Promise}
     */
    _loadDiffAssets(diff) {
      if (isImageDiff(diff)) {
        return this._getImages(diff).then(images => {
          this._baseImage = images.baseImage;
          this._revisionImage = images.revisionImage;
        });
      } else {
        this._baseImage = null;
        this._revisionImage = null;
        return Promise.resolve();
      }
    },

    /**
     * @param {Object} diff
     * @return {boolean}
     */
    _computeIsImageDiff(diff) {
      return isImageDiff(diff);
    },

    _commentsChanged(newComments) {
      const allComments = [];
      for (const side of [GrDiffBuilder.Side.LEFT, GrDiffBuilder.Side.RIGHT]) {
        // This is needed by the threading.
        for (const comment of newComments[side]) {
          comment.__commentSide = side;
        }
        allComments.push(...newComments[side]);
      }
      // Currently, the only way this is ever changed here is when the initial
      // comments are loaded, so it's okay performance wise to clear the threads
      // and recreate them. If this changes in future, we might want to reuse
      // some DOM nodes here.
      this._clearThreads();
      const threads = this._createThreads(allComments);
      for (const thread of threads) {
        const threadEl = this._createThreadElement(thread);
        this._attachThreadElement(threadEl);
      }
    },

    /**
     * @param {!Array<!Object>} comments
     * @return {!Array<!Object>} Threads for the given comments.
     */
    _createThreads(comments) {
      const sortedComments = comments.slice(0).sort((a, b) => {
        if (b.__draft && !a.__draft ) { return 0; }
        if (a.__draft && !b.__draft ) { return 1; }
        return util.parseDate(a.updated) - util.parseDate(b.updated);
      });

      const threads = [];
      for (const comment of sortedComments) {
        // If the comment is in reply to another comment, find that comment's
        // thread and append to it.
        if (comment.in_reply_to) {
          const thread = threads.find(thread =>
              thread.comments.some(c => c.id === comment.in_reply_to));
          if (thread) {
            thread.comments.push(comment);
            continue;
          }
        }

        // Otherwise, this comment starts its own thread.
        const newThread = {
          start_datetime: comment.updated,
          comments: [comment],
          commentSide: comment.__commentSide,
          patchNum: comment.patch_set,
          rootId: comment.id || comment.__draftID,
          lineNum: comment.line,
          isOnParent: comment.side === 'PARENT',
        };
        if (comment.range) {
          newThread.range = Object.assign({}, comment.range);
        }
        threads.push(newThread);
      }
      return threads;
    },

    /**
     * @param {Object} blame
     * @return {boolean}
     */
    _computeIsBlameLoaded(blame) {
      return !!blame;
    },

    /**
     * @param {Object} diff
     * @return {!Promise}
     */
    _getImages(diff) {
      return this.$.restAPI.getImagesForDiff(this.changeNum, diff,
          this.patchRange);
    },

    /** @param {CustomEvent} e */
    _handleCreateComment(e) {
      const {lineNum, side, patchNum, isOnParent, range} = e.detail;
      const threadEl = this._getOrCreateThread(patchNum, lineNum, side, range,
          isOnParent);
      threadEl.addOrEditDraft(lineNum, range);

      this.$.reporting.recordDraftInteraction();
    },

    /**
     * Gets or creates a comment thread at a given location.
     * May provide a range, to get/create a range comment.
     *
     * @param {string} patchNum
     * @param {?number} lineNum
     * @param {string} commentSide
     * @param {Gerrit.Range|undefined} range
     * @param {boolean} isOnParent
     * @return {!Object}
     */
    _getOrCreateThread(patchNum, lineNum, commentSide, range, isOnParent) {
      let threadEl = this._getThreadEl(lineNum, commentSide, range);
      if (!threadEl) {
        threadEl = this._createThreadElement({
          comments: [],
          commentSide,
          patchNum,
          lineNum,
          range,
          isOnParent,
        });
        this._attachThreadElement(threadEl);
      }
      return threadEl;
    },

    _attachThreadElement(threadEl) {
      Polymer.dom(this.$.diff).appendChild(threadEl);
    },

    _clearThreads() {
      for (const threadEl of this.getThreadEls()) {
        const parent = Polymer.dom(threadEl).parentNode;
        Polymer.dom(parent).removeChild(threadEl);
      }
    },

    _createThreadElement(thread) {
      const threadEl = document.createElement('gr-comment-thread');
      threadEl.className = 'comment-thread';
      threadEl.setAttribute('slot', `${thread.commentSide}-${thread.lineNum}`);
      threadEl.comments = thread.comments;
      threadEl.commentSide = thread.commentSide;
      threadEl.isOnParent = !!thread.isOnParent;
      threadEl.parentIndex = this._parentIndex;
      threadEl.changeNum = this.changeNum;
      threadEl.patchNum = thread.patchNum;
      threadEl.lineNum = thread.lineNum;
      const rootIdChangedListener = changeEvent => {
        thread.rootId = changeEvent.detail.value;
      };
      threadEl.addEventListener('root-id-changed', rootIdChangedListener);
      threadEl.path = this.path;
      threadEl.projectName = this.projectName;
      threadEl.range = thread.range;
      const threadDiscardListener = e => {
        const threadEl = /** @type {!Node} */ (e.currentTarget);

        const parent = Polymer.dom(threadEl).parentNode;
        Polymer.dom(parent).removeChild(threadEl);

        threadEl.removeEventListener('root-id-changed', rootIdChangedListener);
        threadEl.removeEventListener('thread-discard', threadDiscardListener);
      };
      threadEl.addEventListener('thread-discard', threadDiscardListener);
      return threadEl;
    },

    /**
     * Gets a comment thread element at a given location.
     * May provide a range, to get a range comment.
     *
     * @param {?number} lineNum
     * @param {string} commentSide
     * @param {!Gerrit.Range=} range
     * @return {?Node}
     */
    _getThreadEl(lineNum, commentSide, range=undefined) {
      let line;
      if (commentSide === GrDiffBuilder.Side.LEFT) {
        line = {beforeNumber: lineNum};
      } else if (commentSide === GrDiffBuilder.Side.RIGHT) {
        line = {afterNumber: lineNum};
      } else {
        throw new Error(`Unknown side: ${commentSide}`);
      }
      function matchesRange(threadEl) {
        const threadRange = /** @type {!Gerrit.Range} */(
            JSON.parse(threadEl.getAttribute('range')));
        return Gerrit.rangesEqual(threadRange, range);
      }

      const filteredThreadEls = this._filterThreadElsForLocation(
          this.getThreadEls(), line, commentSide).filter(matchesRange);
      return filteredThreadEls.length ? filteredThreadEls[0] : null;
    },

    /**
     * @param {!Array<!HTMLElement>} threadEls
     * @param {!{beforeNumber: (number|string|undefined|null),
     *           afterNumber: (number|string|undefined|null)}}
     *     lineInfo
     * @param {!Gerrit.DiffSide=} side The side (LEFT, RIGHT) for
     *     which to return the threads.
     * @return {!Array<!HTMLElement>} The thread elements matching the given
     *     location.
     */
    _filterThreadElsForLocation(threadEls, lineInfo, side) {
      function matchesLeftLine(threadEl) {
        return threadEl.getAttribute('comment-side') ==
            Gerrit.DiffSide.LEFT &&
            threadEl.getAttribute('line-num') == lineInfo.beforeNumber;
      }
      function matchesRightLine(threadEl) {
        return threadEl.getAttribute('comment-side') ==
            Gerrit.DiffSide.RIGHT &&
            threadEl.getAttribute('line-num') == lineInfo.afterNumber;
      }
      function matchesFileComment(threadEl) {
        return threadEl.getAttribute('comment-side') == side &&
              // line/range comments have 1-based line set, if line is falsy it's
              // a file comment
              !threadEl.getAttribute('line-num');
      }

      // Select the appropriate matchers for the desired side and line
      // If side is BOTH, we want both the left and right matcher.
      const matchers = [];
      if (side !== Gerrit.DiffSide.RIGHT) {
        matchers.push(matchesLeftLine);
      }
      if (side !== Gerrit.DiffSide.LEFT) {
        matchers.push(matchesRightLine);
      }
      if (lineInfo.afterNumber === 'FILE' ||
          lineInfo.beforeNumber === 'FILE') {
        matchers.push(matchesFileComment);
      }
      return threadEls.filter(threadEl =>
          matchers.some(matcher => matcher(threadEl)));
    },

    /**
     * Take a diff that was loaded with a ignore-whitespace other than
     * IGNORE_NONE, and convert delta chunks labeled as common into shared
     * chunks.
     * @param {!Object} diff
     * @returns {!Object}
     */
    _translateChunksToIgnore(diff) {
      const newDiff = Object.assign({}, diff);
      const mergedContent = [];

      // Was the last chunk visited a shared chunk?
      let lastWasShared = false;

      for (const chunk of diff.content) {
        if (lastWasShared && chunk.common && chunk.b) {
          // The last chunk was shared and this chunk should be ignored, so
          // add its revision content to the previous chunk.
          mergedContent[mergedContent.length - 1].ab.push(...chunk.b);
        } else if (chunk.common && !chunk.b) {
          // If the chunk should be ignored, but it doesn't have revision
          // content, then drop it and continue without updating lastWasShared.
          continue;
        } else if (lastWasShared && chunk.ab) {
          // Both the last chunk and the current chunk are shared. Merge this
          // chunk's shared content into the previous shared content.
          mergedContent[mergedContent.length - 1].ab.push(...chunk.ab);
        } else if (!lastWasShared && chunk.common && chunk.b) {
          // If the previous chunk was not shared, but this one should be
          // ignored, then add it as a shared chunk.
          mergedContent.push({ab: chunk.b});
        } else {
          // Otherwise add the chunk as is.
          mergedContent.push(chunk);
        }

        lastWasShared = !!mergedContent[mergedContent.length - 1].ab;
      }

      newDiff.content = mergedContent;
      return newDiff;
    },

    _getIgnoreWhitespace() {
      if (!this.prefs || !this.prefs.ignore_whitespace) {
        return WHITESPACE_IGNORE_NONE;
      }
      return this.prefs.ignore_whitespace;
    },

    _whitespaceChanged(preferredWhitespaceLevel, loadedWhitespaceLevel,
        noRenderOnPrefsChange) {
      if (preferredWhitespaceLevel !== loadedWhitespaceLevel &&
          !noRenderOnPrefsChange) {
        this.reload();
      }
    },

    /**
     * @param {Object} patchRangeRecord
     * @return {number|null}
     */
    _computeParentIndex(patchRangeRecord) {
      return this.isMergeParent(patchRangeRecord.base.basePatchNum) ?
          this.getParentIndex(patchRangeRecord.base.basePatchNum) : null;
    },

    _handleCommentSave(e) {
      const comment = e.detail.comment;
      const side = e.detail.comment.__commentSide;
      const idx = this._findDraftIndex(comment, side);
      this.set(['comments', side, idx], comment);
      this._handleCommentSaveOrDiscard();
    },

    _handleCommentDiscard(e) {
      const comment = e.detail.comment;
      this._removeComment(comment);
      this._handleCommentSaveOrDiscard();
    },

    /**
     * Closure annotation for Polymer.prototype.push is off. Submitted PR:
     * https://github.com/Polymer/polymer/pull/4776
     * but for not supressing annotations.
     *
     * @suppress {checkTypes}
     */
    _handleCommentUpdate(e) {
      const comment = e.detail.comment;
      const side = e.detail.comment.__commentSide;
      let idx = this._findCommentIndex(comment, side);
      if (idx === -1) {
        idx = this._findDraftIndex(comment, side);
      }
      if (idx !== -1) { // Update draft or comment.
        this.set(['comments', side, idx], comment);
      } else { // Create new draft.
        this.push(['comments', side], comment);
      }
    },

    _handleCommentSaveOrDiscard() {
      this.dispatchEvent(new CustomEvent('diff-comments-modified',
          {bubbles: true}));
    },

    _removeComment(comment) {
      const side = comment.__commentSide;
      this._removeCommentFromSide(comment, side);
    },

    _removeCommentFromSide(comment, side) {
      let idx = this._findCommentIndex(comment, side);
      if (idx === -1) {
        idx = this._findDraftIndex(comment, side);
      }
      if (idx !== -1) {
        this.splice('comments.' + side, idx, 1);
      }
    },

    /** @return {number} */
    _findCommentIndex(comment, side) {
      if (!comment.id || !this.comments[side]) {
        return -1;
      }
      return this.comments[side].findIndex(item => item.id === comment.id);
    },

    /** @return {number} */
    _findDraftIndex(comment, side) {
      if (!comment.__draftID || !this.comments[side]) {
        return -1;
      }
      return this.comments[side].findIndex(
          item => item.__draftID === comment.__draftID);
    },

    _handleRenderStart() {
      this.$.reporting.time(TimingLabel.TOTAL);
      this.$.reporting.time(TimingLabel.CONTENT);
    },

    _handleRenderContent() {
      this.$.reporting.timeEnd(TimingLabel.CONTENT);
      this.$.reporting.time(TimingLabel.SYNTAX);
    },

    _handleRenderSyntax() {
      this.$.reporting.timeEnd(TimingLabel.SYNTAX);
      this.$.reporting.timeEnd(TimingLabel.TOTAL);
    },

    _handleNormalizeRange(event) {
      this.$.reporting.reportInteraction('normalize-range',
          `Modified invalid comment range on l. ${event.detail.lineNum}` +
          ` of the ${event.detail.side} side`);
    },
  });
})();
