package com.storedobject.test;

import com.storedobject.client.Client;
import com.storedobject.common.IO;
import com.storedobject.common.JSON;
import com.storedobject.common.StringList;
import com.storedobject.common.StringUtility;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A test program to test various features of the {@link Client}.
 *
 * @author Syam
 */
public class Test {

    private static final String HOST = "sodev.saasvaap.com";
    private static final String APP = "aerotrade";

    public static void main(String[] args) {
        tester("Login", Test::login);
        tester("Register OTP", Test::registerOTP);
        tester("OTP Login", Test::otpLogin);
    }

    private static void tester(String name, Function<Client, Boolean> clientConsumer) {
        System.out.println("Testing " + name + "...");
        Client client = new Client(HOST, APP);
        Throwable error = client.getError();
        if (error != null) {
            System.err.println(name + " failed: " + error.getMessage());
            error.printStackTrace();
            return;
        }
        Boolean result = clientConsumer.apply(client);
        client.close();
        System.out.println(name + " " + (result != null && result? "passed" : "failed"));
    }

    private static boolean login(Client client) {
        return client.login("xxx", "SecretPassword").isEmpty();
    }

    private static boolean registerOTP(Client client) {
        String email = "syam@engravgroup.com";
        Map<String, Object> m = new HashMap<>();
        m.put("action", "otp");
        m.put("email", email);
        JSON json = client.command("register", m);
        System.out.println("Response: " + json.toPrettyString());
        if(!"OK".equals(json.getString("status"))) {
            return false;
        }
        long otp = inputOTP("Register with OTP " + json.getString("prefixEmail"));
        if(otp == 0) {
            return false;
        }
        m = new HashMap<>();
        m.put("action", "logic");
        m.put("emailOTP", otp);
        json = client.command("register", m, true);
        System.out.println("Response: " + json.toPrettyString());
        return "OK".equals(json.getString("status"));
    }

    private static boolean otpLogin(Client client) {
        String email = "syam.s.pillai@gmail.com";
        JSON json = client.otp(email);
        System.out.println("Response: " + json.toPrettyString());
        if(!"OK".equals(json.getString("status"))) {
            return false;
        }
        long otp = inputOTP("Register with OTP " + json.getString("prefixEmail"));
        if(otp == 0) {
            return false;
        }
        return "".equals(client.login(otp));
    }

    private static long inputOTP(String prompt) {
        System.out.print(prompt + " ");
        String input = System.console().readLine();
        if(input == null) {
            System.out.println("Abandoned");
            return 0;
        }
        if(!StringUtility.isDigit(input)) {
            System.out.println("Invalid OTP");
            return 0;
        }
        return Long.parseLong(input);
    }


    public static void oldTests() throws IOException {
        Client client;
        client = new Client("storedobject.com", "training");
        Throwable error = client.getError();
        if (error != null) {
            error.printStackTrace();
            return;
        }
        String status = client.login("syam", "SecretPassword");
        if (status.isEmpty()) {

            print("Logged in successfully");

            print("Checking content type (and saving the content to a file)");
            Client.Data data = client.file("Weight Schedule - Approval Letter");
            if (data.error() == null) {
                print("Mime type of the file retrieved is: " + data.mimeType());
                IO.copy(data.stream(), IO.getOutput("/home/syam/test.pdf"), true);
            } else {
                printError("Error retrieving file: " + data.error());
            }

            print("Run a report and save the output to a file");
            data = client.report("com.engravsystems.emqim.operations.logic.TestReport");
            if (data.error() == null) {
                print("Mime type of the report output is: " + data.mimeType());
                IO.copy(data.stream(), IO.getOutput("/home/syam/report.pdf"), true);
            } else {
                printError("Error: " + data.error());
            }

            print("Checking content type, without retrieving the content");
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("name", "Weight Schedule - Approval Letter");
            JSON json = client.command("contentType", attributes);
            print(json);

            print("Upload a file to replace the current content of the one retrieved now");
            if ("OK".equals(json.getString("status"))) {
                print(client.upload("application/pdf",
                        IO.getInput("/home/syam/Documents/Georgia.pdf"),
                        "Weight Schedule - Approval Letter"));
            }

            print("List of persons whose first name starts with the letter N");
            attributes.clear();
            attributes.put("className", "core.Person");
            attributes.put("attributes", StringList.create("TitleValue AS Title", "FirstName", "DateOfBirth"));
            attributes.put("where", "FirstName LIKE 'N%'");
            print(client.command("list", attributes));

            print("List of usernames whose first name starts with the letter N");
            attributes.clear();
            attributes.put("className", "core.SystemUser");
            attributes.put("attributes", StringList.create(
                    "Id",
                    "Login",
                    "Person.Name AS FN",
                    "Person.DateOfBirth AS DoB",
                    "Person.MaritalStatusValue AS MaritalStatus"));
            attributes.put("where", "Person.FirstName LIKE 'N%'");
            attributes.put("order", "Person.FirstName");
            print(client.command("list", attributes));

            print("A person whose name starts with N (Note: The fist person found is returned)");
            attributes.clear();
            attributes.put("className", "core.Person");
            attributes.put("where", "FirstName LIKE 'N%'");
            print(client.command("get", attributes));

            client.logout();
        } else {
            printError("Unable to login, error: " + status);
            client.close();
        }
    }

    private static void print(Object anything) {
        if (anything instanceof JSON result) {
            if ("OK".equals(result.getString("status"))) {
                JSON json = result.get("data");
                System.out.println(Objects.requireNonNullElse(json, result).toPrettyString());
            } else {
                System.err.println("Error: " + result.getString("message"));
            }
        } else {
            System.out.println();
            System.out.println(anything);
        }
    }

    private static void printError(Object anything) {
        System.err.println();
        System.err.println(anything);
    }
}