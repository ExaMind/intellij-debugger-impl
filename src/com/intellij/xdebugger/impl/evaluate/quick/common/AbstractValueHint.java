// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl.evaluate.quick.common;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.ide.TooltipEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.impl.EditorComponentImpl;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.*;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.DocumentUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.impl.actions.XDebuggerActions;
import com.intellij.xdebugger.impl.evaluate.quick.XDebuggerTreeCreator;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.ExecutionPointHighlighter;
import com.intellij.xdebugger.impl.ui.tree.nodes.HeadlessValueEvaluationCallbackBase;
import com.intellij.xdebugger.ui.DebuggerColors;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.EventObject;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class AbstractValueHint {
  private static final Logger LOG = Logger.getInstance(AbstractValueHint.class);

  private final KeyListener myEditorKeyListener = new KeyAdapter() {
    @Override
    public void keyReleased(KeyEvent e) {
      if (!isAltMask(e.getModifiers())) {
        ValueLookupManager.getInstance(myProject).hideHint();
      }
    }
  };

  private RangeHighlighter myHighlighter;
  private boolean myCursorSet;
  private final Project myProject;
  private final Editor myEditor;
  private final ValueHintType myType;
  protected final Point myPoint;
  private EditorMouseEvent myEditorMouseEvent;

  // TODO: now hint could be shown as a LightweightHint or a JBPopup
  private LightweightHint myCurrentHint;
  private JBPopup myCurrentPopup;

  private boolean myHintHidden;
  private TextRange myCurrentRange;
  private Runnable myHideRunnable;

  public AbstractValueHint(@NotNull Project project, @NotNull Editor editor, @NotNull Point point, @NotNull ValueHintType type,
                           final TextRange textRange) {
    myPoint = point;
    myProject = project;
    myEditor = editor;
    myType = type;
    myCurrentRange = textRange;
  }

  protected abstract boolean canShowHint();

  protected abstract void evaluateAndShowHint();

  boolean isInsideCurrentRange(Editor editor, Point point) {
    return myCurrentRange != null && myCurrentRange.contains(calculateOffset(editor, point));
  }

  public static int calculateOffset(@NotNull Editor editor, @NotNull Point point) {
    return editor.logicalPositionToOffset(editor.xyToLogicalPosition(point));
  }

  public void hideHint() {
    myHintHidden = true;
    myCurrentRange = null;
    if (myCursorSet) {
      myCursorSet = false;
      if (myEditor instanceof EditorEx) ((EditorEx)myEditor).setCustomCursor(AbstractValueHint.class, null);
      if (LOG.isDebugEnabled()) {
        LOG.debug("restore cursor in editor");
      }
      myEditor.getContentComponent().removeKeyListener(myEditorKeyListener);
    }

    hideCurrentHint();
    disposeHighlighter();
  }

  void disposeHighlighter() {
    if (myHighlighter != null) {
      myHighlighter.dispose();
      myHighlighter = null;
    }
  }

  public void invokeHint() {
    invokeHint(null);
  }

  public void invokeHint(Runnable hideRunnable) {
    myHideRunnable = hideRunnable;

    if (!canShowHint() || !isCurrentRangeValid()) {
      hideHint();
      return;
    }

    createHighlighter();
    if (myType != ValueHintType.MOUSE_ALT_OVER_HINT) {
      evaluateAndShowHint();
    }
  }

  private static final Key<TextAttributes> HINT_TEXT_ATTRIBUTES = Key.create("HINT_TEXT_ATTRIBUTES");

  private void setHighlighterAttributes() {
    if (myHighlighter != null) {
      TextAttributes attributes = myHighlighter.getUserData(HINT_TEXT_ATTRIBUTES);
      if (attributes != null) {
        ((RangeHighlighterEx)myHighlighter).setTextAttributes(attributes);
      }
    }
  }

  private void createHighlighter() {
    TextAttributes attributes;
    if (myType == ValueHintType.MOUSE_ALT_OVER_HINT) {
      attributes = myEditor.getColorsScheme().getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR);
      attributes = NavigationUtil.patchAttributesColor(attributes, myCurrentRange, myEditor);
    }
    else {
      attributes = new TextAttributes(); // real attributes will be stored in user data
    }

    disposeHighlighter();
    myHighlighter = myEditor.getMarkupModel().addRangeHighlighter(myCurrentRange.getStartOffset(), myCurrentRange.getEndOffset(),
                                                                  HighlighterLayer.SELECTION, attributes,
                                                                  HighlighterTargetArea.EXACT_RANGE);
    if (myType == ValueHintType.MOUSE_ALT_OVER_HINT) {
      myEditor.getContentComponent().addKeyListener(myEditorKeyListener);
      if (myEditor instanceof EditorEx) ((EditorEx)myEditor).setCustomCursor(AbstractValueHint.class, hintCursor());
      if (LOG.isDebugEnabled()) {
        LOG.debug("set hint cursor to editor");
      }
      myCursorSet = true;
    }
    else {
      TextAttributesKey attributesKey = DebuggerColors.EVALUATED_EXPRESSION_ATTRIBUTES;
      MarkupModel model = DocumentMarkupModel.forDocument(myEditor.getDocument(), myProject, false);
      if (model != null && !((MarkupModelEx)model).processRangeHighlightersOverlappingWith(
        myCurrentRange.getStartOffset(), myCurrentRange.getEndOffset(),
        h -> !ExecutionPointHighlighter.EXECUTION_POINT_HIGHLIGHTER_TOP_FRAME_KEY.get(h, false))) {
        attributesKey = DebuggerColors.EVALUATED_EXPRESSION_EXECUTION_LINE_ATTRIBUTES;
      }
      myHighlighter.putUserData(HINT_TEXT_ATTRIBUTES, myEditor.getColorsScheme().getAttributes(attributesKey));
    }
  }

  private static Cursor hintCursor() {
    return Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
  }

  public final Project getProject() {
    return myProject;
  }

  @NotNull
  protected final Editor getEditor() {
    return myEditor;
  }

  protected ValueHintType getType() {
    return myType;
  }

  protected final void hideCurrentHint() {
    if (myCurrentHint != null) {
      myCurrentHint.hide();
      myCurrentHint = null;
    }
    if (myCurrentPopup != null) {
      myCurrentPopup.cancel();
      myCurrentPopup = null;
    }
  }

  private boolean myInsideShow = false; // to avoid invoking myHideRunnable for new popups with updated presentation

  private void invokeHideRunnableIfNeeded() {
    if (myHideRunnable != null && !myInsideShow) {
      myHideRunnable.run();
    }
  }

  protected boolean showHint(final JComponent component) {
    myInsideShow = true;
    try {
      hideCurrentHint();
      myCurrentHint = new LightweightHint(component) {
        @Override
        protected boolean canAutoHideOn(TooltipEvent event) {
          InputEvent inputEvent = event.getInputEvent();
          if (inputEvent instanceof MouseEvent) {
            Component comp = inputEvent.getComponent();
            if (comp instanceof EditorComponentImpl) {
              EditorImpl editor = ((EditorComponentImpl)comp).getEditor();
              return !isInsideCurrentRange(editor, ((MouseEvent)inputEvent).getPoint());
            }
          }
          return true;
        }
      };
      myCurrentHint.addHintListener(new HintListener() {
        @Override
        public void hintHidden(@NotNull EventObject event) {
          invokeHideRunnableIfNeeded();
          onHintHidden();
        }
      });

      // editor may be disposed before later invokator process this action
      if (myEditor.isDisposed() || myEditor.getComponent().getRootPane() == null) {
        return false;
      }

      AppUIUtil.targetToDevice(myCurrentHint.getComponent(), myEditor.getComponent());
      Point p = HintManagerImpl.getHintPosition(myCurrentHint, myEditor, myEditor.xyToLogicalPosition(myPoint), HintManager.UNDER);
      HintHint hint = HintManagerImpl.createHintHint(myEditor, p, myCurrentHint, HintManager.UNDER, true);
      hint.setShowImmediately(true);
      HintManagerImpl.getInstanceImpl().showEditorHint(myCurrentHint, myEditor, p,
                                                       HintManager.HIDE_BY_ANY_KEY |
                                                       HintManager.HIDE_BY_TEXT_CHANGE |
                                                       HintManager.HIDE_BY_SCROLLING, 0, false,
                                                       hint);
      if (myHighlighter == null && isCurrentRangeValid()) { // hint text update
        createHighlighter();
      }
      setHighlighterAttributes();
    }
    finally {
      myInsideShow = false;
    }
    return true;
  }

  private boolean isCurrentRangeValid() {
    return myCurrentRange != null && DocumentUtil.isValidOffset(myCurrentRange.getEndOffset(), myEditor.getDocument());
  }

  protected void onHintHidden() {
    disposeHighlighter();
  }

  protected boolean isHintHidden() {
    return myHintHidden;
  }

  protected JComponent createExpandableHintComponent(@Nullable Icon icon,
                                                     final SimpleColoredText text,
                                                     final Runnable expand,
                                                     @Nullable XFullValueEvaluator evaluator) {
    SimpleColoredComponent component = HintUtil.createInformationComponent();
    component.setIcon(icon != null
                      ? IconManager.getInstance().createRowIcon(UIUtil.getTreeCollapsedIcon(), icon)
                      : UIUtil.getTreeCollapsedIcon());
    component.setCursor(hintCursor());
    text.appendToComponent(component);
    appendEvaluatorLink(evaluator, component);
    new ClickListener() {
      @Override
      public boolean onClick(@NotNull MouseEvent e, int clickCount) {
        if (SwingUtilities.isLeftMouseButton(e)) {
          Object tag = ((SimpleColoredComponent)e.getSource()).getFragmentTagAt(e.getX());
          if (tag != null) {
            if (tag instanceof Consumer consumer) {
              //noinspection unchecked
              consumer.accept(e);
            }
            else {
              ((Runnable)tag).run();
            }
          }
          else {
            hideCurrentHint();
            expand.run();
          }
          return true;
        }
        return false;
      }
    }.installOn(component);
    return component;
  }

  protected final void appendEvaluatorLink(@Nullable XFullValueEvaluator evaluator, SimpleColoredComponent component) {
    if (evaluator != null) {
      component.append(
        evaluator.getLinkText(),
        XDebuggerTreeNodeHyperlink.TEXT_ATTRIBUTES,
        (Consumer<MouseEvent>)event -> {
          if (evaluator.isShowValuePopup()) {
            DebuggerUIUtil.showValuePopup(evaluator, event, getProject(), getEditor());
          }
          else {
            new HeadlessValueEvaluationCallbackBase(getProject()).startFetchingValue(evaluator);
          }
        }
      );
    }
  }

  @Nullable
  protected TextRange getCurrentRange() {
    return myCurrentRange;
  }

  private static boolean isAltMask(@JdkConstants.InputEventMask int modifiers) {
    return KeymapUtil.matchActionMouseShortcutsModifiers(KeymapManager.getInstance().getActiveKeymap(),
                                                         modifiers,
                                                         XDebuggerActions.QUICK_EVALUATE_EXPRESSION);
  }

  @Nullable
  public static ValueHintType getHintType(final EditorMouseEvent e) {
    int modifiers = e.getMouseEvent().getModifiers();
    if (modifiers == 0) {
      return ValueHintType.MOUSE_OVER_HINT;
    }
    else if (isAltMask(modifiers)) {
      return ValueHintType.MOUSE_ALT_OVER_HINT;
    }
    return null;
  }

  protected boolean isShowing() {
    return myCurrentHint != null || myCurrentPopup != null;
  }

  public boolean isInsideHint(Editor editor, Point point) {
    return myCurrentHint != null && myCurrentHint.isInsideHint(new RelativePoint(editor.getContentComponent(), point));
  }

  private void showPopup(Function<Point, JBPopup> popupPresenter) {
    myInsideShow = true;
    try {
      if (myEditor.isDisposed() || !isCurrentRangeValid()) {
        hideHint();
        return;
      }

      hideCurrentHint();

      createHighlighter();
      setHighlighterAttributes();

      // align the popup with the bottom of the line
      Point point = myEditor.visualPositionToXY(myEditor.xyToVisualPosition(myPoint));
      point.translate(0, myEditor.getLineHeight());

      myCurrentPopup = popupPresenter.apply(point);
      myEditor.getScrollingModel().addVisibleAreaListener(e -> {
        if (!Objects.equals(e.getOldRectangle(), e.getNewRectangle())) {
          hideCurrentHint();
        }
      }, myCurrentPopup);
      myEditor.getCaretModel().addCaretListener(new CaretListener() {
        @Override
        public void caretPositionChanged(@NotNull CaretEvent event) {
          hideCurrentHint();
        }

        @Override
        public void caretAdded(@NotNull CaretEvent event) {
          hideCurrentHint();
        }

        @Override
        public void caretRemoved(@NotNull CaretEvent event) {
          hideCurrentHint();
        }
      }, myCurrentPopup);
      myCurrentPopup.addListener(new JBPopupListener() {
        @Override
        public void onClosed(@NotNull LightweightWindowEvent event) {
          invokeHideRunnableIfNeeded();
          onHintHidden();
        }
      });
    }
    finally {
      myInsideShow = false;
    }
  }

  protected <D> void showTreePopup(@NotNull DebuggerTreeCreator<D> creator, @NotNull D descriptor) {
    showPopup(point -> new XDebuggerTreePopup<>(creator, myEditor, point, myProject, null).show(descriptor));
  }

  @ApiStatus.Experimental
  protected void showTextPopup(@NotNull XDebuggerTreeCreator creator,
                               @NotNull Pair<XValue, String> descriptor,
                               @NotNull String initialText,
                               @Nullable XFullValueEvaluator evaluator) {
    showPopup(point ->
                new XDebuggerTextPopup<>(evaluator, descriptor.first, creator, descriptor, myEditor, point, myProject, null)
                  .show(initialText)
    );
  }

  protected void showTooltipPopup(JComponent component) {
    showPopup(point -> new XDebuggerTooltipPopup(myEditor, point).show(component, myEditorMouseEvent));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    AbstractValueHint hint = (AbstractValueHint)o;

    if (!myProject.equals(hint.myProject)) return false;
    if (!myEditor.equals(hint.myEditor)) return false;
    if (myType != hint.myType) return false;
    if (!Objects.equals(myCurrentRange, hint.myCurrentRange)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hash(myProject, myEditor, myType, myCurrentRange);
  }

  void setEditorMouseEvent(EditorMouseEvent editorMouseEvent) {
    myEditorMouseEvent = editorMouseEvent;
  }
}
