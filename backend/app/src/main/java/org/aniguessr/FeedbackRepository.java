package org.aniguessr;

/**
 * Where submitted feedback goes. An interface for the same reason {@link AnimeRepository}
 * is one: the route can be tested without a database behind it.
 */
public interface FeedbackRepository {

    void save(Feedback feedback);
}
