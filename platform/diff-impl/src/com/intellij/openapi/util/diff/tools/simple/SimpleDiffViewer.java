package com.intellij.openapi.util.diff.tools.simple;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.DiffBundle;
import com.intellij.openapi.diff.DiffNavigationContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.diff.actions.BufferedLineIterator;
import com.intellij.openapi.util.diff.actions.NavigationContextChecker;
import com.intellij.openapi.util.diff.api.FrameDiffTool.DiffContext;
import com.intellij.openapi.util.diff.comparison.DiffTooBigException;
import com.intellij.openapi.util.diff.contents.DocumentContent;
import com.intellij.openapi.util.diff.fragments.LineFragment;
import com.intellij.openapi.util.diff.fragments.LineFragmentImpl;
import com.intellij.openapi.util.diff.fragments.LineFragments;
import com.intellij.openapi.util.diff.requests.ContentDiffRequest;
import com.intellij.openapi.util.diff.requests.DiffRequest;
import com.intellij.openapi.util.diff.tools.util.*;
import com.intellij.openapi.util.diff.tools.util.DiffUserDataKeys.ScrollToPolicy;
import com.intellij.openapi.util.diff.tools.util.base.HighlightPolicy;
import com.intellij.openapi.util.diff.tools.util.twoside.TwosideTextDiffViewer;
import com.intellij.openapi.util.diff.util.CalledInAwt;
import com.intellij.openapi.util.diff.util.DiffUtil;
import com.intellij.openapi.util.diff.util.DiffUtil.DocumentData;
import com.intellij.openapi.util.diff.util.Side;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.LightweightHint;
import com.intellij.util.ui.AnimatedIcon;
import com.intellij.util.ui.AsyncProcessIcon;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

class SimpleDiffViewer extends TwosideTextDiffViewer {
  public static final Logger LOG = Logger.getInstance(SimpleDiffViewer.class);

  @NotNull private final SyncScrollSupport.SyncScrollable mySyncScrollable;
  @NotNull private final PrevNextDifferenceIterable myPrevNextDifferenceIterable;
  @NotNull private final MyStatusPanel myStatusPanel;

  @NotNull private final List<SimpleDiffChange> myDiffChanges = new ArrayList<SimpleDiffChange>();
  @NotNull private final List<SimpleDiffChange> myInvalidDiffChanges = new ArrayList<SimpleDiffChange>();

  public SimpleDiffViewer(@NotNull DiffContext context, @NotNull DiffRequest request) {
    super(context, (ContentDiffRequest)request);

    mySyncScrollable = new MySyncScrollable();
    myPrevNextDifferenceIterable = new MyPrevNextDifferenceIterable();
    myStatusPanel = new MyStatusPanel();
  }

  @Override
  protected void onInit() {
    super.onInit();
    myContentPanel.setPainter(new MyDividerPainter());
  }

  @Override
  protected void onDisposeAwt() {
    super.onDisposeAwt();
    destroyChangedBlocks();
  }

  @NotNull
  @Override
  protected List<AnAction> createToolbarActions() {
    List<AnAction> group = new ArrayList<AnAction>();

    group.add(new MyIgnorePolicySettingAction());
    group.add(new MyHighlightPolicySettingAction());
    group.add(new MyToggleAutoScrollAction());
    group.add(myEditorSettingsAction);

    return group;
  }

  @Nullable
  @Override
  protected List<AnAction> createPopupActions() {
    List<AnAction> group = new ArrayList<AnAction>();

    group.add(Separator.getInstance());
    group.add(new MyIgnorePolicySettingAction().getPopupGroup());
    group.add(Separator.getInstance());
    group.add(new MyHighlightPolicySettingAction().getPopupGroup());
    group.add(Separator.getInstance());
    group.add(new MyToggleAutoScrollAction());

    return group;
  }

  @NotNull
  @Override
  protected List<AnAction> createEditorPopupActions() {
    List<AnAction> group = new ArrayList<AnAction>();

    group.add(new MyApplySelectedChangesAction());

    return group;
  }

  //
  // Diff
  //

  @Override
  protected void onBeforeRediff() {
    myStatusPanel.setBusy(true);
  }

  @Override
  @NotNull
  protected Runnable performRediff(@NotNull final ProgressIndicator indicator) {
    try {
      indicator.checkCanceled();

      assert myActualContent1 != null || myActualContent2 != null;

      if (myActualContent1 == null) {
        final DocumentContent content = myActualContent2;
        final Document document = content.getDocument();

        CompareData data = ApplicationManager.getApplication().runReadAction(new Computable<CompareData>() {
          @Override
          public CompareData compute() {
            LineFragmentImpl fragment = new LineFragmentImpl(0, 0, 0, getLineCount(document),
                                                             0, 0, 0, document.getTextLength());
            LineFragments fragments = LineFragments.create(Collections.singletonList(fragment));
            return new CompareData(fragments, false, 0, document.getModificationStamp());
          }
        });

        return apply(data);
      }

      if (myActualContent2 == null) {
        final DocumentContent content = myActualContent1;
        final Document document = content.getDocument();

        CompareData data = ApplicationManager.getApplication().runReadAction(new Computable<CompareData>() {
          @Override
          public CompareData compute() {
            LineFragmentImpl fragment = new LineFragmentImpl(0, getLineCount(document), 0, 0,
                                                             0, document.getTextLength(), 0, 0);
            LineFragments fragments = LineFragments.create(Collections.singletonList(fragment));
            return new CompareData(fragments, false, document.getModificationStamp(), 0);
          }
        });

        return apply(data);
      }

      final DocumentContent content1 = myActualContent1;
      final DocumentContent content2 = myActualContent2;
      final Document document1 = content1.getDocument();
      final Document document2 = content2.getDocument();

      DocumentData data = ApplicationManager.getApplication().runReadAction(new Computable<DocumentData>() {
        @Override
        public DocumentData compute() {
          return new DocumentData(document1.getImmutableCharSequence(), document2.getImmutableCharSequence(),
                                  document1.getModificationStamp(), document2.getModificationStamp());
        }
      });

      LineFragments lineFragments = null;
      if (getHighlightPolicy().isShouldCompare()) {
        lineFragments = DiffUtil.compareWithCache(myRequest, data, getDiffConfig(), indicator);
      }

      boolean isEqualContents = (lineFragments == null || lineFragments.getFragments().isEmpty()) &&
                                StringUtil.equals(document1.getCharsSequence(), document2.getCharsSequence());

      return apply(new CompareData(lineFragments, isEqualContents, data.getStamp1(), data.getStamp2()));
    }
    catch (DiffTooBigException ignore) {
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addTooBigContentNotification();
        }
      };
    }
    catch (ProcessCanceledException ignore) {
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addOperationCanceledNotification();
        }
      };
    }
    catch (Exception e) {
      LOG.error(e);
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addDiffErrorNotification();
        }
      };
    }
    catch (final Error e) {
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addDiffErrorNotification();
          throw e;
        }
      };
    }
  }

  @NotNull
  private Runnable apply(@NotNull final CompareData data) {
    return new Runnable() {
      @Override
      public void run() {
        if (myEditor1 != null && myEditor1.getDocument().getModificationStamp() != data.getStamp1()) return;
        if (myEditor2 != null && myEditor2.getDocument().getModificationStamp() != data.getStamp2()) return;

        clearDiffPresentation();

        if (data.isEqualContent()) myPanel.addContentsEqualNotification();

        if (data.getFragments() != null) {
          for (LineFragment fragment : data.getFragments().getFragments()) {
            myDiffChanges.add(new SimpleDiffChange(fragment, myEditor1, myEditor2, getHighlightPolicy().isFineFragments()));
          }
        }

        scrollOnRediff();

        myContentPanel.repaintDivider();
        myStatusPanel.update();
      }
    };
  }

  private void clearDiffPresentation() {
    myStatusPanel.setBusy(false);
    myPanel.resetNotifications();
    destroyChangedBlocks();
  }

  @NotNull
  private DiffUtil.DiffConfig getDiffConfig() {
    return new DiffUtil.DiffConfig(getTextSettings().getIgnorePolicy(), getHighlightPolicy());
  }

  @NotNull
  private HighlightPolicy getHighlightPolicy() {
    return getTextSettings().getHighlightPolicy();
  }

  //
  // Impl
  //

  private void destroyChangedBlocks() {
    for (SimpleDiffChange change : myDiffChanges) {
      change.destroyHighlighter();
    }
    myDiffChanges.clear();

    for (SimpleDiffChange change : myInvalidDiffChanges) {
      change.destroyHighlighter();
    }
    myInvalidDiffChanges.clear();

    myContentPanel.repaintDivider();
    myStatusPanel.update();
  }

  @Override
  @CalledInAwt
  protected void onBeforeDocumentChange(@NotNull DocumentEvent e) {
    super.onBeforeDocumentChange(e);
    if (myDiffChanges.isEmpty()) return;

    assert myEditor1 != null && myEditor2 != null;

    Side side;
    if (e.getDocument() == myEditor1.getDocument()) {
      side = Side.LEFT;
    }
    else if (e.getDocument() == myEditor2.getDocument()) {
      side = Side.RIGHT;
    }
    else {
      LOG.warn("Unknown document changed");
      return;
    }

    int offset1 = e.getOffset();
    int offset2 = e.getOffset() + e.getOldLength();

    if (e.getOldLength() != 0 && e.getNewLength() != 0 &&
        StringUtil.endsWithChar(e.getOldFragment(), '\n') &&
        StringUtil.endsWithChar(e.getNewFragment(), '\n')) {
      offset2--;
    }

    int line1 = e.getDocument().getLineNumber(offset1);
    int line2 = e.getDocument().getLineNumber(offset2) + 1;
    int shift = StringUtil.countNewLines(e.getNewFragment()) - StringUtil.countNewLines(e.getOldFragment());

    List<SimpleDiffChange> invalid = new ArrayList<SimpleDiffChange>();
    for (SimpleDiffChange change : myDiffChanges) {
      if (change.processChange(line1, line2, shift, side)) {
        invalid.add(change);
      }
    }

    if (!invalid.isEmpty()) {
      myDiffChanges.removeAll(invalid);
      myInvalidDiffChanges.addAll(invalid);
    }
  }

  @CalledInAwt
  @Override
  protected boolean doScrollToChange(@NotNull ScrollToPolicy scrollToPolicy) {
    if (myDiffChanges.isEmpty()) return false;

    SimpleDiffChange targetChange;
    switch (scrollToPolicy) {
      case FIRST_CHANGE:
        targetChange = myDiffChanges.get(0);
        break;
      case LAST_CHANGE:
        targetChange = myDiffChanges.get(myDiffChanges.size() - 1);
        break;
      default:
        throw new IllegalArgumentException(scrollToPolicy.name());
    }

    EditorEx editor = getCurrentEditor();
    int line = targetChange.getStartLine(getCurrentSide());
    DiffUtil.scrollEditor(editor, line);

    return true;
  }

  @Override
  protected boolean doScrollToContext(@NotNull DiffNavigationContext context) {
    if (myEditor2 == null) return false;

    ChangedLinesIterator changedLinesIterator = new ChangedLinesIterator(Side.RIGHT);
    NavigationContextChecker checker = new NavigationContextChecker(changedLinesIterator, context);
    int line = checker.contextMatchCheck();
    if (line == -1) {
      // this will work for the case, when spaces changes are ignored, and corresponding fragments are not reported as changed
      // just try to find target line  -> +-
      AllLinesIterator allLinesIterator = new AllLinesIterator(Side.RIGHT);
      NavigationContextChecker checker2 = new NavigationContextChecker(allLinesIterator, context);
      line = checker2.contextMatchCheck();
    }
    if (line == -1) return false;

    scrollToLine(Side.RIGHT, line);
    return true;
  }

  //
  // Getters
  //

  int getCurrentStartLine(@NotNull SimpleDiffChange change) {
    return change.getStartLine(getCurrentSide());
  }

  int getCurrentEndLine(@NotNull SimpleDiffChange change) {
    return change.getEndLine(getCurrentSide());
  }

  @NotNull
  List<SimpleDiffChange> getDiffChanges() {
    return myDiffChanges;
  }

  @NotNull
  @Override
  protected SyncScrollSupport.SyncScrollable getSyncScrollable() {
    return mySyncScrollable;
  }

  @NotNull
  @Override
  protected JComponent getStatusPanel() {
    return myStatusPanel.getComponent();
  }

  //
  // Misc
  //

  @SuppressWarnings("MethodOverridesStaticMethodOfSuperclass")
  public static boolean canShowRequest(@NotNull DiffContext context, @NotNull DiffRequest request) {
    return TwosideTextDiffViewer.canShowRequest(context, request);
  }

  //
  // Actions
  //

  private class MyPrevNextDifferenceIterable implements PrevNextDifferenceIterable {
    @Override
    public void notify(@NotNull String message) {
      final LightweightHint hint = new LightweightHint(HintUtil.createInformationLabel(message));
      HintManagerImpl.getInstanceImpl().showEditorHint(hint, getCurrentEditor(), HintManager.UNDER,
                                                       HintManager.HIDE_BY_ANY_KEY |
                                                       HintManager.HIDE_BY_TEXT_CHANGE |
                                                       HintManager.HIDE_BY_SCROLLING,
                                                       0, false);
    }

    @Override
    public boolean canGoNext() {
      if (myDiffChanges.isEmpty()) return false;

      EditorEx editor = getCurrentEditor();
      int line = editor.getCaretModel().getLogicalPosition().line;
      if (line == editor.getDocument().getLineCount() - 1) return false;

      SimpleDiffChange lastChange = myDiffChanges.get(myDiffChanges.size() - 1);
      if (getCurrentStartLine(lastChange) <= line) return false;

      return true;
    }

    @Override
    public void goNext() {
      EditorEx editor = getCurrentEditor();

      int line = editor.getCaretModel().getLogicalPosition().line;

      SimpleDiffChange next = null;
      for (int i = 0; i < myDiffChanges.size(); i++) {
        SimpleDiffChange change = myDiffChanges.get(i);
        if (getCurrentStartLine(change) <= line) continue;

        next = change;
        break;
      }

      assert next != null;

      DiffUtil.scrollToLineAnimated(editor, getCurrentStartLine(next));
    }

    @Override
    public boolean canGoPrev() {
      if (myDiffChanges.isEmpty()) return false;

      EditorEx editor = getCurrentEditor();
      int line = editor.getCaretModel().getLogicalPosition().line;
      if (line == 0) return false;

      SimpleDiffChange firstChange = myDiffChanges.get(0);
      if (getCurrentEndLine(firstChange) > line) return false;
      if (getCurrentStartLine(firstChange) >= line) return false;

      return true;
    }

    @Override
    public void goPrev() {
      EditorEx editor = getCurrentEditor();

      int line = editor.getCaretModel().getLogicalPosition().line;

      SimpleDiffChange prev = null;
      for (int i = 0; i < myDiffChanges.size(); i++) {
        SimpleDiffChange change = myDiffChanges.get(i);

        SimpleDiffChange next = i < myDiffChanges.size() - 1 ? myDiffChanges.get(i + 1) : null;
        if (next == null || getCurrentEndLine(next) > line || getCurrentStartLine(next) >= line) {
          prev = change;
          break;
        }
      }

      assert prev != null;

      DiffUtil.scrollToLineAnimated(editor, getCurrentStartLine(prev));
    }
  }

  private class MyApplySelectedChangesAction extends AnAction implements DumbAware {
    public MyApplySelectedChangesAction() {
      super("Replace selected", null, AllIcons.Diff.Arrow); // TODO: rotate arrow in for popup in left editor
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Editor editor = e.getData(CommonDataKeys.EDITOR);
      Side side = Side.fromLeft(editor == myEditor1);
      EditorEx oEditor = side.select(myEditor2, myEditor1);

      if (editor == null || oEditor == null) {
        e.getPresentation().setEnabled(false);
        return;
      }

      if (editor != myEditor1 && editor != myEditor2) {
        e.getPresentation().setEnabled(false);
        return;
      }

      if (myDiffChanges.isEmpty()) {
        e.getPresentation().setEnabled(false);
        return;
      }

      if (!isSomeChangeSelected(editor, side)) {
        e.getPresentation().setEnabled(false);
        return;
      }

      if (!DiffUtil.canMakeWritable(oEditor.getDocument())) {
        e.getPresentation().setEnabled(false);
        return;
      }

      e.getPresentation().setEnabled(true);
    }

    @Override
    public void actionPerformed(@NotNull final AnActionEvent e) {
      Editor editor = e.getData(CommonDataKeys.EDITOR);
      final Side side = Side.fromLeft(editor == myEditor1);
      EditorEx oEditor = side.select(myEditor2, myEditor1);

      assert editor != null && oEditor != null;

      final BitSet lines = DiffUtil.getSelectedLines(editor);

      DiffUtil.executeWriteCommand(oEditor.getDocument(), e.getProject(), "Replace selected changes", new Runnable() {
        @Override
        public void run() {
          for (int i = myDiffChanges.size() - 1; i >= 0; i--) {
            SimpleDiffChange change = myDiffChanges.get(i);
            int line1 = change.getStartLine(side);
            int line2 = change.getEndLine(side);

            if (DiffUtil.isSelectedByLine(lines, line1, line2)) {
              change.replaceChange(side);
            }
          }
        }
      });
    }

    protected boolean isSomeChangeSelected(@NotNull Editor editor, @NotNull Side side) {
      List<Caret> carets = editor.getCaretModel().getAllCarets();
      if (carets.size() != 1) return true;
      Caret caret = carets.get(0);
      if (caret.hasSelection()) return true;
      int line = caret.getLogicalPosition().line;

      for (SimpleDiffChange change : myDiffChanges) {
        if (change.isSelectedByLine(line, side)) return true;
      }
      return false;
    }
  }

  //
  // Scroll from annotate
  //

  private class AllLinesIterator implements Iterator<Pair<Integer, CharSequence>> {
    @NotNull private final Side mySide;
    @NotNull private final Document myDocument;
    private int myLine = 0;

    private AllLinesIterator(@NotNull Side side) {
      mySide = side;

      Editor editor = mySide.select(myEditor1, myEditor2);
      assert editor != null;
      myDocument = editor.getDocument();
    }

    @Override
    public boolean hasNext() {
      return myLine < getLineCount(myDocument);
    }

    @Override
    public Pair<Integer, CharSequence> next() {
      int offset1 = myDocument.getLineStartOffset(myLine);
      int offset2 = myDocument.getLineEndOffset(myLine);

      CharSequence text = myDocument.getImmutableCharSequence().subSequence(offset1, offset2);

      Pair<Integer, CharSequence> pair = new Pair<Integer, CharSequence>(myLine, text);
      myLine++;

      return pair;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  private class ChangedLinesIterator extends BufferedLineIterator {
    @NotNull private final Side mySide;
    private int myIndex = 0;

    private ChangedLinesIterator(@NotNull Side side) {
      mySide = side;
      init();
    }

    @Override
    public boolean hasNextBlock() {
      return myIndex < myDiffChanges.size();
    }

    @Override
    public void loadNextBlock() {
      SimpleDiffChange change = myDiffChanges.get(myIndex);
      myIndex++;

      int line1 = change.getStartLine(mySide);
      int line2 = change.getEndLine(mySide);

      Editor editor = mySide.select(myEditor1, myEditor2);
      assert editor != null;
      Document document = editor.getDocument();

      for (int i = line1; i < line2; i++) {
        int offset1 = document.getLineStartOffset(i);
        int offset2 = document.getLineEndOffset(i);

        CharSequence text = document.getImmutableCharSequence().subSequence(offset1, offset2);
        addLine(i, text);
      }
    }
  }

  //
  // Helpers
  //

  @Nullable
  @Override
  public Object getData(@NonNls String dataId) {
    if (DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.is(dataId)) {
      return myPrevNextDifferenceIterable;
    }
    else {
      return super.getData(dataId);
    }
  }

  private class MySyncScrollable extends BaseSyncScrollable {
    @Override
    public boolean isSyncScrollEnabled() {
      return getTextSettings().isEnableSyncScroll();
    }

    public int transfer(@NotNull Side side, int line) {
      if (myDiffChanges.isEmpty()) {
        return line;
      }

      return super.transfer(side, line);
    }

    @Override
    protected void processHelper(@NotNull ScrollHelper helper) {
      assert myEditor1 != null && myEditor2 != null;

      if (!helper.process(0, 0)) return;
      for (SimpleDiffChange diffChange : myDiffChanges) {
        if (!helper.process(diffChange.getStartLine(Side.LEFT), diffChange.getStartLine(Side.RIGHT))) return;
        if (!helper.process(diffChange.getEndLine(Side.LEFT), diffChange.getEndLine(Side.RIGHT))) return;
      }
      helper.process(myEditor1.getDocument().getLineCount(), myEditor2.getDocument().getLineCount());
    }
  }

  private class MyDividerPainter implements DiffSplitter.Painter, DividerPolygon.DividerPaintable {
    @Override
    public void paint(@NotNull Graphics g, @NotNull Component divider) {
      if (myEditor1 == null || myEditor2 == null) return;
      Graphics2D gg = getDividerGraphics(g, divider);

      int width = divider.getWidth();
      //DividerPolygon.paintSimplePolygons(gg, DividerPolygon.createVisiblePolygons(myEditor1, myEditor2, this), width);
      DividerPolygon.paintPolygons(gg, DividerPolygon.createVisiblePolygons(myEditor1, myEditor2, this), width);
      gg.dispose();
    }

    @Override
    public void process(@NotNull Handler handler) {
      for (SimpleDiffChange diffChange : myDiffChanges) {
        if (!handler.process(diffChange.getStartLine(Side.LEFT), diffChange.getEndLine(Side.LEFT),
                             diffChange.getStartLine(Side.RIGHT), diffChange.getEndLine(Side.RIGHT),
                             diffChange.getDiffType().getColor(myEditor1))) {
          return;
        }
      }
    }
  }

  private class MyStatusPanel {
    private final JPanel myPanel;
    private final JLabel myTextLabel;
    private final AnimatedIcon myBusySpinner;

    public MyStatusPanel() {
      myTextLabel = new JLabel("");
      myBusySpinner = new AsyncProcessIcon("SimpleDiffViewer");

      myPanel = new JPanel(new BorderLayout());
      myPanel.add(myTextLabel, BorderLayout.CENTER);
      myPanel.add(myBusySpinner, BorderLayout.WEST);
    }

    @NotNull
    public JComponent getComponent() {
      return myPanel;
    }

    public void update() {
      int changes = myDiffChanges.size() + myInvalidDiffChanges.size();
      myTextLabel.setText(DiffBundle.message("diff.count.differences.status.text", changes));
    }

    public void setBusy(boolean busy) {
      if (busy) {
        myBusySpinner.resume();
      }
      else {
        myBusySpinner.suspend();
      }
    }
  }

  private static class CompareData {
    @Nullable private final LineFragments myFragments;
    private final boolean myEqualContent;
    private final long myStamp1;
    private final long myStamp2;

    public CompareData(@Nullable LineFragments fragments, boolean equalContent, long stamp1, long stamp2) {
      myFragments = fragments;
      myEqualContent = equalContent;
      myStamp1 = stamp1;
      myStamp2 = stamp2;
    }

    @Nullable
    public LineFragments getFragments() {
      return myFragments;
    }

    public boolean isEqualContent() {
      return myEqualContent;
    }

    public long getStamp1() {
      return myStamp1;
    }

    public long getStamp2() {
      return myStamp2;
    }
  }
}
