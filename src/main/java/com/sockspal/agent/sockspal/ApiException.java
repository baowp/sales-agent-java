package com.sockspal.agent.sockspal;

/** The SocksPal API failed or a lookup found nothing. The message is shown to the model. */
public class ApiException extends RuntimeException {

    public ApiException(String message) {
        super(message);
    }
}
