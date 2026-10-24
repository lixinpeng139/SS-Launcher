package com.sslauncher.ai.assistant;

/**
 * The small surface the tool layer needs from the JavaFX assistant page.
 * Everything is called from the assistant worker thread, never the FX thread.
 */
public interface AssistantUi {
    /**
     * Shows a blocking launcher confirmation. Returns false when the user
     * declined, closed the dialog or did not answer before the timeout.
     */
    boolean confirm(String title, String detail, String confirmLabel);

    /** Shows a cancellation-capable progress strip for one tool execution. */
    Operation beginOperation(String title);

    /** Asks the page to refresh instances and other launcher views. */
    void refreshLauncher();

    interface Operation {
        void progress(String stage, String detail);

        boolean isCancelled();

        void finish();
    }

    /** Used by headless protocol tests; never auto-confirms installs. */
    AssistantUi HEADLESS = new AssistantUi() {
        @Override
        public boolean confirm(String title, String detail, String confirmLabel) {
            return false;
        }

        @Override
        public Operation beginOperation(String title) {
            return new Operation() {
                @Override
                public void progress(String stage, String detail) {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public void finish() {
                }
            };
        }

        @Override
        public void refreshLauncher() {
        }
    };
}
