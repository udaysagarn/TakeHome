package ai.devin.mend.web;

import ai.devin.mend.engine.EngineControl;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the engine's state on every rendered page, because the switch that changes it lives in the
 * navigation and a button that cannot show what it is about to do is worse than no button.
 */
@ControllerAdvice(assignableTypes = DashboardController.class)
public class EngineAdvice {

    private final EngineControl engine;

    public EngineAdvice(EngineControl engine) {
        this.engine = engine;
    }

    @ModelAttribute("engine")
    public EngineControl.Status engine() {
        return engine.status();
    }
}
