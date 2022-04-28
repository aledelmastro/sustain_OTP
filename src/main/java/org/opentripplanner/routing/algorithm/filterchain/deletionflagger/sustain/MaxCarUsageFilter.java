package org.opentripplanner.routing.algorithm.filterchain.deletionflagger.sustain;

import java.util.function.Predicate;

import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.routing.algorithm.filterchain.deletionflagger.ItineraryDeletionFlagger;
import org.opentripplanner.routing.core.TraverseMode;

/**
 * Il filtro flagga i percorsi in cui l'automobile viene utilizzata
 * per un periodo di tempo superiore al consentito.
 * @author https://github.com/aledelmastro
 */
public class MaxCarUsageFilter implements ItineraryDeletionFlagger {
    public static final String TAG = "too-long-to-allow-car";

    private final long limit;

    public MaxCarUsageFilter(long limitInSeconds) {
        this.limit = limitInSeconds;
    }

    @Override
    public String name() {
        return TAG;
    }

    @Override
    public Predicate<Itinerary> predicate() {
        return itinerary -> {
            double carDuration = itinerary.legs
                .stream()
                .filter(l -> l.getMode() == TraverseMode.CAR)
                .mapToDouble(Leg::getDuration)
                .sum();

            return carDuration > limit;
        };
    }
}
