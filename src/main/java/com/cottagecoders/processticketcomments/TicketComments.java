package com.cottagecoders.processticketcomments;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.zendesk.client.v2.Zendesk;
import org.zendesk.client.v2.model.Comment;
import org.zendesk.client.v2.model.CustomFieldValue;
import org.zendesk.client.v2.model.Status;
import org.zendesk.client.v2.model.Ticket;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TicketComments {

  private static final String OUTPUT_FILE = "./k8s.txt";

  @Parameter(names = {"-s", "--start"}, description = "Start date (oldest) yyyy-MM-dd format. Dates are inclusive.")
  private String start = "";
  @Parameter(names = {"-e", "--end"}, description = "End date (most recent). yyyy-MM-dd format. Dates are inclusive.")
  private String end = "";

  public static void main(String[] args) {
    TicketComments tc = new TicketComments();
    tc.run(args);
    System.exit(0);

  }

  private void run(String[] args) {

    PrintWriter pw = null;
    try {
      pw = new PrintWriter(OUTPUT_FILE);

    } catch (IOException ex) {
      System.out.println(String.format("Exception opening PrintWriter %s", OUTPUT_FILE));
      ex.printStackTrace();
      System.exit(4);
    }

    // process command line args.
    JCommander.newBuilder().addObject(this).build().parse(args);

    // process command line dates..
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    Date startDate = null;
    Date endDate = null;
    try {
      startDate = sdf.parse(start);
      endDate = sdf.parse(end);

    } catch (ParseException ex) {
      System.out.println("Exception parsing dates");
      System.exit(6);
    }

    Zendesk zd = null;
    try {
      // set up Zendesk connection
      zd = new Zendesk.Builder(System.getenv("ZENDESK_URL")).setUsername(System.getenv("ZENDESK_EMAIL")).setToken(System.getenv(
              "ZENDESK_TOKEN")).build();

    } catch (Exception ex) {
      System.out.println("Exception: " + ex.getMessage());
      ex.printStackTrace();
      System.exit(1);
    }


    Map<Long, OrgSummary> ticketCounts = new HashMap<>();

    // we do not use Zendesk search; we do not know up fron how many tickets will be returned:
    // we have a warning from this page: https://support.zendesk.com/hc/en-us/articles/4408886879258#topic_ghr_wsc_3v
    // "Also, search returns only the first 1,000 results even if there are more results."
    // in testing, we get HTTP/422 when querying more than 1000 tickets.
    int total = 0;
    int inDateRange = 0;
    int kubectl = 0;

    for (Ticket t : zd.getTickets()) {

      ++total;
      // backwards date logic, because we want to include the start and end dates.
      // eg. there is only < or >.  no <= >=    :)

      // ticket are not in date order, must process all tickets.
      // also note that if we search with the searchTerm, this date-checking code is not needed
      // (and the dates for the search term are NOT inclusive.)
      if (t.getCreatedAt().before(startDate) || t.getCreatedAt().after(endDate)) {
        continue;
      }

      if (!t.getStatus().equals(Status.CLOSED) && !t.getStatus().equals(Status.SOLVED)) {
        continue;
      }

      // verify that the ord ig and the org name are not null.
      if (t.getOrganizationId() == null || zd.getOrganization(t.getOrganizationId()).getName() == null) {
        System.out.println("ticket " + t.getId() + " Organization id  or organization name is null");
        continue; //skip this ticket.
      }

      ++inDateRange;
      // check if we've had this Org before
      if (ticketCounts.get(t.getOrganizationId()) == null) {
        ticketCounts.put(t.getOrganizationId(),
                         new OrgSummary(0, 0, zd.getOrganization(t.getOrganizationId()).getName()));
      }

      // check for K8s stuff here.
      boolean k8s = false;
      OrgSummary os = ticketCounts.get(t.getOrganizationId());
      if (deploymentIsAKSEKS(t, zd)) {
        os.setK8sPlusOne();

      } else {
        // check comments until we find kubectl
        for (Comment c : zd.getTicketComments(t.getId())) {
          if (c.getBody().toLowerCase().contains("kubectl")) {
            System.out.println("ticket " + t.getId() + " got kubectl");
            k8s = true;
            ++kubectl;
            break;
          }
        }

        if (k8s) {
          os.setK8sPlusOne();
        } else {
          os.setNotK8sPlusOne();
        }
      }
    }

    System.out.println(String.format("total tickets %d  in date range: %d", total, inDateRange));

    // end of ticket gathering and processing. Print results here.
    // transform Map to List of values
    List<OrgSummary> items = new ArrayList<>(ticketCounts.values());

    // sort in place.
    Collections.sort(items, new Comparator<OrgSummary>() {
      public int compare(OrgSummary left, OrgSummary right) {
        // case insensitive sort.
        return left.getOrgName().toUpperCase().compareTo(right.getOrgName().toUpperCase());
      }
    });

    printTotals(inDateRange, kubectl, items, pw);

    System.out.println("Dates: " + sdf.format(startDate) + "  and " + sdf.format(endDate));
    printThem("\n\nSort by name", items, pw);

    // print by k8s count, ascending:
    // sort in place.
    Collections.sort(items, new Comparator<OrgSummary>() {
      public int compare(OrgSummary left, OrgSummary right) {
        // integer sorting DESCENDING ORDER
        return right.getK8s().compareTo(left.getK8s());
      }
    });

    printThem("\n\nSort by K8s ticket count", items, pw);
    pw.close();
  }

  boolean deploymentIsAKSEKS(Ticket t, Zendesk zd) {
    if (t.getCustomFields() != null) {
      for (CustomFieldValue cf : t.getCustomFields()) {
        if (cf != null) {
          if (cf.getId() == 1260826362790L) {
            if (cf.getValue() != null) {
              for (String s : cf.getValue()) {
                if (s.contains("deploy_azure_aks") || s.contains("deploy_aws_eks")) {
                  return true;
                }
              }
            }
          }
        }
      }
    }

    return false;
  }

  void printTotals(int total, int kubectl, List<OrgSummary> items, PrintWriter pw) {
    int k8 = 0;
    int notK8 = 0;
    for (OrgSummary os : items) {
      k8 += os.getK8s();
      notK8 += os.getNotK8s();
    }
    pw.println(String.format(
            "time range: %s through %s\n" + "Orgs with tickets: %d\n" + "Orgs with K8s tickets: %d\n"
                    + "Tickets in time range: %d\n" + "kubectl tickets (not AKS, EKS): %d\n"
                    + "Total Kubernetes Tickets: %d\n" + "Non Kubernetes Tickets: %d\n",
                             start,
                             end,
                             items.size(),
                             orgsWithK8s(items),
                             total,
                             kubectl,
                             k8,
                             notK8));
  }

  int orgsWithK8s(List<OrgSummary> items) {
    int orgCount = 0;
    for (OrgSummary os : items) {
      if (os.getK8s() != 0) {
        orgCount++;
      }
    }
    return orgCount;
  }

  private void printThem(String title, List<OrgSummary> items, PrintWriter pw) {
    // title row.
    String fmt2 = "%-30s  %10s  %10s";
    // data row.
    String fmt = "%-30s  %10d  %10d";
    pw.println(title);
    pw.println(String.format(fmt2, "Org Name", "K8s tickets", "non-K8s tickets"));
    for (OrgSummary s : items) {
      String message = String.format("%-30s  %-10d  %-10d", s.getOrgName(), s.getK8s(), s.getNotK8s());
      pw.println(message);
    }
  }
}

class OrgSummary {
  private Integer k8s;
  private Integer notK8s;
  private String orgName;

  OrgSummary(Integer k8s, Integer notK8s, String orgName) {
    this.k8s = k8s;
    this.notK8s = notK8s;
    this.orgName = orgName;
  }

  public Integer getK8s() {
    return k8s;
  }

  public void setK8sPlusOne() {
    this.k8s += 1;
  }

  public Integer getNotK8s() {
    return notK8s;
  }

  public void setNotK8sPlusOne() {
    this.notK8s += 1;
  }

  public String getOrgName() {
    return orgName;
  }
}
