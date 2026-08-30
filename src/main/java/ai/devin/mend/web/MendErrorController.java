package ai.devin.mend.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Spring's Whitelabel page. A mistyped task id in front of an audience should still look
 * like the product, and should say what to do next.
 */
@Controller
public class MendErrorController implements ErrorController {

    @RequestMapping("/error")
    public String error(HttpServletRequest request, Model model) {
        HttpStatus status = resolve(request);
        model.addAttribute("status", status.value() + " " + status.getReasonPhrase());
        model.addAttribute("headline", headline(status));
        model.addAttribute("detail", detail(status));
        model.addAttribute("path", request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        return "error";
    }

    private static HttpStatus resolve(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        HttpStatus status = HttpStatus.resolve(Integer.parseInt(code.toString()));
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    private static String headline(HttpStatus status) {
        return status == HttpStatus.NOT_FOUND ? "Nothing here" : "menD hit an error";
    }

    private static String detail(HttpStatus status) {
        return status == HttpStatus.NOT_FOUND
                ? "That page, task or repository does not exist. Every task menD knows about is on the flow board."
                : "The request could not be completed. The task itself is unaffected — menD's state lives in the "
                        + "database, not in this page.";
    }
}
