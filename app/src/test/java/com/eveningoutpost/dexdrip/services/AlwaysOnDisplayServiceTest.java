package com.eveningoutpost.dexdrip.services;

import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;

import org.junit.Test;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Config(sdk = 28)
public class AlwaysOnDisplayServiceTest extends RobolectricTestWithConfig {

    private static class TestService extends AlwaysOnDisplayService {
        final WindowManager manager = mock(WindowManager.class);
        List<AccessibilityWindowInfo> windows;

        @Override
        public Object getSystemService(String name) {
            return manager;
        }

        @Override
        public List<AccessibilityWindowInfo> getWindows() {
            return windows;
        }
    }

    private AccessibilityWindowInfo window() {
        final AccessibilityWindowInfo window = mock(AccessibilityWindowInfo.class);
        doAnswer(invocation -> {
            ((Rect) invocation.getArgument(0)).set(0, 0, 1080, 2400);
            return null;
        }).when(window).getBoundsInScreen(any(Rect.class));
        return window;
    }

    @Test
    public void positioningContinuesWithUnavailableRootAndSkipsOwnOverlay() throws Exception {
        final TestService service = new TestService();
        final AccessibilityWindowInfo unavailable = window();
        final AccessibilityWindowInfo overlay = window();
        when(overlay.getType()).thenReturn(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY);
        when(overlay.getTitle()).thenReturn("xDrip Always On");
        service.windows = Arrays.asList(unavailable, overlay);
        final View view = setView(service);

        service.rejigLayout();

        verify(overlay, never()).getRoot();
        verify(service.manager).updateViewLayout(same(view), any(WindowManager.LayoutParams.class));
    }

    @Test
    public void positioningContinuesWhenChildrenDisappear() throws Exception {
        final TestService service = new TestService();
        final AccessibilityWindowInfo window = window();
        final AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        final AccessibilityNodeInfo child = mock(AccessibilityNodeInfo.class);
        when(window.getRoot()).thenReturn(root);
        when(root.getChildCount()).thenReturn(2);
        when(root.getChild(1)).thenReturn(child);
        when(child.getClassName()).thenReturn("android.widget.FrameLayout");
        when(child.getChildCount()).thenReturn(1);
        service.windows = Arrays.asList(window);
        final View view = setView(service);

        service.rejigLayout();

        verify(service.manager).updateViewLayout(same(view), any(WindowManager.LayoutParams.class));
    }

    private View setView(TestService service) throws Exception {
        final View view = mock(View.class);
        final Field field = AlwaysOnDisplayService.class.getDeclaredField("aodView");
        field.setAccessible(true);
        field.set(service, view);
        return view;
    }
}
