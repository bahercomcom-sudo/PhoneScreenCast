package com.baher.phonescreencast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Process-wide streaming state. The service publishes, the activity observes.
 * Because it lives outside the activity, the UI survives rotation / recreation.
 */
public final class StreamRepository {

    public enum State { IDLE, STARTING, STREAMING }

    public static final class Status {
        @NonNull public final State state;
        /** Viewer URL, or null when there is no usable network address. */
        @Nullable public final String url;
        @NonNull public final String viewerStatus;
        /** Last error, shown while idle. */
        @Nullable public final String error;

        public Status(@NonNull State state, @Nullable String url,
                      @NonNull String viewerStatus, @Nullable String error) {
            this.state = state;
            this.url = url;
            this.viewerStatus = viewerStatus;
            this.error = error;
        }

        public static Status idle(@Nullable String error) {
            return new Status(State.IDLE, null, "", error);
        }

        public static Status starting() {
            return new Status(State.STARTING, null, "", null);
        }
    }

    private static final MutableLiveData<Status> STATUS = new MutableLiveData<>(Status.idle(null));
    private static volatile Status current = Status.idle(null);

    private StreamRepository() {}

    public static LiveData<Status> status() {
        return STATUS;
    }

    public static Status current() {
        return current;
    }

    /** Safe to call from any thread. */
    public static void publish(@NonNull Status status) {
        current = status;
        STATUS.postValue(status);
    }
}
