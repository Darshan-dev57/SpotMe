package dev.darshan.spotme.removal;

import org.springframework.stereotype.Component;

/**
 * The eraser used until a real one is added. The models tested in eval/erase_test.py need about
 * 1 GB of memory, which the hosted box does not have, so this one only says it is not set up.
 * An erase job that reaches it fails and the photo stays hidden; the organizer can keep it instead.
 */
@Component
public class NoPersonEraser implements PersonEraser {

    @Override
    public byte[] erase(byte[] jpeg, FaceBox face) {
        throw new IllegalStateException("no person eraser is set up on this server");
    }
}
