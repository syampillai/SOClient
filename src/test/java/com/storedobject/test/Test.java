package com.storedobject.test;

import com.storedobject.client.Client;
import com.storedobject.common.IO;
import com.storedobject.common.JSON;
import com.storedobject.common.StringList;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A test program to test various features of the {@link Client}.
 *
 * @author Syam
 */
public class Test {

    public static void main(String[] args) throws IOException {
        Client client = new Client("emqim12.engravsystems.com", "emqimtest");
        String status = client.login("username", "password");
        if(status.isEmpty()) {
            System.out.println("Logged in successfully");
            Client.Data data = client.file("Weight Schedule - Approval Letter");
            if (data.error() == null) {
                print("Mime type of the file retrieved is: " + data.mimeType());
                IO.copy(data.stream(), IO.getOutput("/home/syam/test.pdf"), true);
            } else {
                print("Error retrieving file: " + data.error());
            }
            Map<String, Object> attributes = new HashMap<>();
            print("List of persons whose first name starts with the letter N");
            attributes.put("className", "core.Person");
            attributes.put("attributes", StringList.create("TitleValue AS Title", "FirstName", "DateOfBirth"));
            attributes.put("where", "FirstName LIKE 'N%'");
            printData(client.command("list", attributes));
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
            printData(client.command("list", attributes));
            print("A person whose name starts with N (Note: The fist person found is returned)");
            attributes.clear();
            attributes.put("className", "core.Person");
            attributes.put("where", "FirstName LIKE 'N%'");
            printData(client.command("get", attributes));
            client.logout();
        } else {
            System.err.println("Unable to login, error: " + status);
        }
    }

    private static void printData(JSON result) {
        if("OK".equals(result.getString("status"))) {
            System.out.println(result.get("data").toPrettyString());
        } else {
            System.err.println("Error: " + result.getString("message"));
        }
    }


    private static void print(Object anything) {
        System.out.println(anything);
    }
}
