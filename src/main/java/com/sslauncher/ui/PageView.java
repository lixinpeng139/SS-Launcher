package com.sslauncher.ui;

import javafx.scene.Node;

public interface PageView {
    Node node();

    default void onShow() {
    }
}
