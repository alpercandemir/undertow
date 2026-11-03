package example;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class PaymentStatus {
    static int statusCode(ResponseEntity<String> response) {
        HttpStatus status = response.getStatusCode();
        return status.value();
    }

    public static void main(String[] args) {
        for (int expected : new int[] {200, 503}) {
            int actual = statusCode(ResponseEntity.status(expected).body("demo"));
            if (actual != expected) {
                throw new AssertionError("Expected " + expected + ", got " + actual);
            }
        }
        System.out.println("PASS: response status codes 200 and 503 preserved");
    }
}
