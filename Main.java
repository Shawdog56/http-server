import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter Connection Pool Size: ");
        int poolSize = sc.nextInt();

        try {
            // Only start the primary. The secondary starts inside CustomHttpServer logic.
            CustomHttpServer primary = new CustomHttpServer(8080, 8081, poolSize);
            primary.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}