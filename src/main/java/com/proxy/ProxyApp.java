package com.proxy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Main window: hosts one {@link ProxyPanel} (= one project, one proxy) per tab.
 * The trailing "+" tab opens a new, unsaved project tab, and the set of open
 * projects is remembered so they are restored on the next start.
 */
public class ProxyApp extends JFrame {

    private final JTabbedPane tabs = new JTabbedPane();
    /** Suppresses the "+"-tab handler while tabs are added/removed in code. */
    private boolean adjustingTabs;

    public ProxyApp() {
        setTitle("TCP/UDP Proxy");
        setSize(1540, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(UiTheme.APP_BG);

        add(buildHeaderBar(), BorderLayout.NORTH);

        tabs.setFont(UiTheme.BASE);
        tabs.setBackground(UiTheme.APP_BG);
        tabs.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 4));
        add(tabs, BorderLayout.CENTER);

        adjustingTabs = true;
        addPlusTab();
        for (String name : initialProjects()) {
            insertProjectTab(name);
        }
        if (tabs.getTabCount() == 1) { // only the "+" tab — no stored projects
            insertProjectTab(ProjectStore.DEFAULT_PROJECT);
        }
        adjustingTabs = false;
        tabs.setSelectedIndex(0);

        tabs.addChangeListener(e -> {
            if (!adjustingTabs && tabs.getSelectedIndex() == plusTabIndex()) {
                // Clicking the "+" tab creates a fresh, unsaved project tab.
                SwingUtilities.invokeLater(() -> addNewTab(null));
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                for (ProxyPanel panel : panels()) {
                    panel.shutdown();
                }
            }
        });
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildHeaderBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(UiTheme.HEADER_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));

        JLabel title = new JLabel("TCP / UDP  Proxy");
        title.setFont(UiTheme.TITLE);
        title.setForeground(UiTheme.HEADER_FG);
        bar.add(title, BorderLayout.WEST);
        return bar;
    }

    // ------------------------------------------------------------------ tabs

    /** Projects to open at startup: stored open list, filtered to unique names. */
    private List<String> initialProjects() {
        Set<String> unique = new LinkedHashSet<>();
        for (String name : ProjectStore.openProjects()) {
            unique.add(ProjectStore.sanitize(name));
        }
        return new ArrayList<>(unique);
    }

    private int plusTabIndex() {
        return tabs.getTabCount() - 1;
    }

    /** The dummy trailing tab that acts as a "new tab" button. */
    private void addPlusTab() {
        JPanel placeholder = new JPanel();
        placeholder.setBackground(UiTheme.APP_BG);
        tabs.addTab("+", placeholder);

        JLabel plus = new JLabel("  +  ");
        plus.setFont(UiTheme.BOLD);
        plus.setForeground(UiTheme.MUTED);
        plus.setToolTipText("새 프로젝트 탭");
        plus.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                addNewTab(null);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                plus.setForeground(UiTheme.ACCENT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                plus.setForeground(UiTheme.MUTED);
            }
        });
        tabs.setTabComponentAt(tabs.getTabCount() - 1, plus);
    }

    /** Inserts a project tab just before the "+" tab (no selection change). */
    private ProxyPanel insertProjectTab(String projectName) {
        ProxyPanel panel = new ProxyPanel(this, projectName);
        int index = plusTabIndex();
        tabs.insertTab(panel.displayName(), null, panel, null, index);
        tabs.setTabComponentAt(index, new TabHeader(panel));
        return panel;
    }

    /** Adds a tab and selects it; {@code projectName} may be null for a new blank tab. */
    private void addNewTab(String projectName) {
        adjustingTabs = true;
        ProxyPanel panel = insertProjectTab(projectName);
        adjustingTabs = false;
        tabs.setSelectedComponent(panel);
        syncOpenProjects();
    }

    /** Closes a tab, stopping its proxy first (with confirmation while running). */
    private void closeTab(ProxyPanel panel) {
        if (panel.isRunning()
                && JOptionPane.showConfirmDialog(this,
                    "'" + panel.displayName() + "' 프록시가 실행 중입니다. 중지하고 탭을 닫을까요?",
                    "탭 닫기", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        panel.shutdown();
        adjustingTabs = true;
        tabs.remove(panel);
        // Never leave the "+" tab selected or as the only tab.
        if (tabs.getTabCount() == 1) {
            insertProjectTab(null);
        }
        if (tabs.getSelectedIndex() == plusTabIndex()) {
            tabs.setSelectedIndex(plusTabIndex() - 1);
        }
        adjustingTabs = false;
        syncOpenProjects();
    }

    private List<ProxyPanel> panels() {
        List<ProxyPanel> list = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component c = tabs.getComponentAt(i);
            if (c instanceof ProxyPanel) {
                list.add((ProxyPanel) c);
            }
        }
        return list;
    }

    /** Persists the ordered list of saved projects currently open as tabs. */
    private void syncOpenProjects() {
        List<String> names = new ArrayList<>();
        for (ProxyPanel panel : panels()) {
            if (panel.getProjectName() != null) {
                names.add(panel.getProjectName());
            }
        }
        ProjectStore.setOpenProjects(names);
    }

    // ------------------------------------------------------ ProxyPanel callbacks

    /** Called by a panel when its project name or running state changed. */
    void panelStateChanged(ProxyPanel panel) {
        int index = tabs.indexOfComponent(panel);
        if (index >= 0) {
            Component header = tabs.getTabComponentAt(index);
            if (header instanceof TabHeader) {
                ((TabHeader) header).refresh();
            }
        }
        syncOpenProjects();
    }

    /** True if another tab (not {@code caller}) already holds {@code name}. */
    boolean isProjectOpenElsewhere(ProxyPanel caller, String name) {
        for (ProxyPanel panel : panels()) {
            if (panel != caller && name.equals(panel.getProjectName())) {
                return true;
            }
        }
        return false;
    }

    /** Switches to the tab holding {@code name}, if any; returns whether it did. */
    boolean focusProjectTab(ProxyPanel caller, String name) {
        for (ProxyPanel panel : panels()) {
            if (panel != caller && name.equals(panel.getProjectName())) {
                tabs.setSelectedComponent(panel);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tab header

    /** Tab title: running indicator + project name + close button. */
    private class TabHeader extends JPanel {

        private final ProxyPanel panel;
        private final JLabel dot = new JLabel("●");
        private final JLabel title = new JLabel();

        TabHeader(ProxyPanel panel) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            this.panel = panel;
            setOpaque(false);

            dot.setFont(UiTheme.BASE);
            title.setFont(UiTheme.BASE);
            title.setForeground(UiTheme.TEXT);

            JLabel close = new JLabel(" × ");
            close.setFont(UiTheme.BOLD);
            close.setForeground(UiTheme.MUTED);
            close.setToolTipText("탭 닫기");
            close.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    closeTab(panel);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    close.setForeground(UiTheme.DANGER);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    close.setForeground(UiTheme.MUTED);
                }
            });

            // A custom tab component swallows clicks, so forward them to select the tab.
            MouseAdapter select = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int index = tabs.indexOfComponent(panel);
                    if (index >= 0) {
                        tabs.setSelectedIndex(index);
                    }
                }
            };
            addMouseListener(select);
            title.addMouseListener(select);
            dot.addMouseListener(select);

            add(dot);
            add(title);
            add(close);
            refresh();
        }

        void refresh() {
            dot.setForeground(panel.isRunning() ? UiTheme.SUCCESS : UiTheme.BORDER);
            title.setText(panel.displayName());
            revalidate();
            repaint();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UiTheme.installDefaults();
            ProxyApp proxyApp = new ProxyApp();
            proxyApp.setVisible(true);
        });
    }
}
