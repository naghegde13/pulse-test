#!/usr/bin/env python3
import csv, random, string, uuid, os
from datetime import datetime, timedelta, date

random.seed(42)
OUT = os.path.join(os.path.dirname(__file__), "loan_master.csv")
N = 500

# Reference data
STATES = ["CA","TX","FL","NY","IL","PA","OH","GA","NC","MI","NJ","VA","WA","AZ","MA",
          "TN","IN","MO","MD","WI","CO","MN","SC","AL","LA","KY","OR","OK","CT","UT"]
CITIES = {"CA":"Los Angeles","TX":"Houston","FL":"Miami","NY":"New York","IL":"Chicago",
          "PA":"Philadelphia","OH":"Columbus","GA":"Atlanta","NC":"Charlotte","MI":"Detroit",
          "NJ":"Newark","VA":"Richmond","WA":"Seattle","AZ":"Phoenix","MA":"Boston",
          "TN":"Nashville","IN":"Indianapolis","MO":"Kansas City","MD":"Baltimore",
          "WI":"Milwaukee","CO":"Denver","MN":"Minneapolis","SC":"Charleston","AL":"Birmingham",
          "LA":"New Orleans","KY":"Louisville","OR":"Portland","OK":"Oklahoma City",
          "CT":"Hartford","UT":"Salt Lake City"}
ZIPS = {"CA":"90001","TX":"77001","FL":"33101","NY":"10001","IL":"60601","PA":"19101",
        "OH":"43201","GA":"30301","NC":"28201","MI":"48201","NJ":"07101","VA":"23218",
        "WA":"98101","AZ":"85001","MA":"02101","TN":"37201","IN":"46201","MO":"64101",
        "MD":"21201","WI":"53201","CO":"80201","MN":"55401","SC":"29401","AL":"35201",
        "LA":"70112","KY":"40201","OR":"97201","OK":"73101","CT":"06101","UT":"84101"}
LOAN_TYPES = ["Conventional","FHA","VA","USDA","Jumbo"]
PROPERTY_TYPES = ["Single Family","Condo","Townhouse","Multi-Family","Manufactured"]
OCCUPANCY = ["Primary Residence","Second Home","Investment Property"]
LOAN_PURPOSE = ["Purchase","Rate/Term Refinance","Cash-Out Refinance","Streamline Refinance"]
LOAN_STATUS = ["Current","30 Days Delinquent","60 Days Delinquent","90+ Days Delinquent",
               "Forbearance","Modification","Paid Off","Foreclosure","REO"]
PAYMENT_FREQ = ["Monthly","Bi-Weekly"]
ESCROW_STATUS = ["Escrowed","Non-Escrowed","Partial Escrow"]
RATE_TYPE = ["Fixed","ARM 5/1","ARM 7/1","ARM 10/1"]
DOC_TYPE = ["Full Documentation","Low Documentation","No Documentation","Stated Income"]
CHANNEL = ["Retail","Wholesale","Correspondent","Broker"]
SERVICER = ["PulseServ Inc","National Loan Services","Pacific Mortgage Corp","Atlantic Servicing Group"]
INVESTOR = ["Fannie Mae","Freddie Mac","Ginnie Mae","Private Label","Portfolio"]
FIRST_NAMES = ["James","Mary","Robert","Patricia","John","Jennifer","Michael","Linda","David",
               "Elizabeth","William","Barbara","Richard","Susan","Joseph","Jessica","Thomas",
               "Sarah","Christopher","Karen","Charles","Lisa","Daniel","Nancy","Matthew","Betty",
               "Anthony","Margaret","Mark","Sandra","Donald","Ashley","Steven","Dorothy","Paul",
               "Kimberly","Andrew","Emily","Joshua","Donna"]
LAST_NAMES = ["Smith","Johnson","Williams","Brown","Jones","Garcia","Miller","Davis","Rodriguez",
              "Martinez","Hernandez","Lopez","Gonzalez","Wilson","Anderson","Thomas","Taylor",
              "Moore","Jackson","Martin","Lee","Perez","Thompson","White","Harris","Sanchez",
              "Clark","Ramirez","Lewis","Robinson"]
MARITAL = ["Single","Married","Divorced","Widowed"]
EMPLOYMENT = ["Employed","Self-Employed","Retired","Government","Military"]
INSURANCE_TYPES = ["HO-3","HO-6","HO-4","HO-8"]
FLOOD_ZONE = ["X","A","AE","V","VE","B","C","D"]
PMI_PROVIDER = ["MGIC","Radian","Essent","National MI","Arch MI","None"]
BANKRUPTCY = ["None","Chapter 7","Chapter 13"]
MODIFICATION_TYPE = ["None","HAMP","Proprietary","FHA Partial Claim","VA IRRL"]
COBORROWER_REL = ["None","Spouse","Domestic Partner","Relative","Non-Relative"]

def rand_date(start_year, end_year):
    s = date(start_year,1,1)
    e = date(end_year,12,31)
    delta = (e - s).days
    return s + timedelta(days=random.randint(0, delta))

def rand_ssn():
    return f"{random.randint(100,999)}-{random.randint(10,99)}-{random.randint(1000,9999)}"

def rand_phone():
    return f"({random.randint(200,999)}) {random.randint(200,999)}-{random.randint(1000,9999)}"

def rand_email(first, last):
    domains = ["gmail.com","yahoo.com","outlook.com","aol.com","icloud.com","hotmail.com"]
    return f"{first.lower()}.{last.lower()}{random.randint(1,99)}@{random.choice(domains)}"

def rand_account():
    return ''.join(random.choices(string.digits, k=10))

rows = []
for i in range(N):
    state = random.choice(STATES)
    city = CITIES[state]
    zipcode = ZIPS[state]
    first = random.choice(FIRST_NAMES)
    last = random.choice(LAST_NAMES)
    dob = rand_date(1955, 1998)
    origination_date = rand_date(2015, 2025)
    maturity_date = origination_date.replace(year=origination_date.year + random.choice([15,20,30]))
    loan_amount = round(random.uniform(80000, 1200000), 2)
    appraised_value = round(loan_amount / random.uniform(0.60, 0.97), 2)
    ltv = round((loan_amount / appraised_value) * 100, 2)
    interest_rate = round(random.uniform(2.5, 7.75), 3)
    rate_type = random.choice(RATE_TYPE)
    margin = round(random.uniform(1.5, 3.5), 3) if "ARM" in rate_type else None
    rate_cap = round(random.uniform(2.0, 5.0), 2) if "ARM" in rate_type else None
    rate_floor = round(random.uniform(1.5, 3.0), 2) if "ARM" in rate_type else None
    next_rate_adj = rand_date(2025, 2030).isoformat() if "ARM" in rate_type else ""
    pi_payment = round(loan_amount * (interest_rate/100/12) / (1 - (1 + interest_rate/100/12)**(-360)), 2)
    escrow_monthly = round(random.uniform(150, 800), 2)
    total_payment = round(pi_payment + escrow_monthly, 2)
    upb = round(loan_amount * random.uniform(0.40, 0.99), 2)
    loan_status = random.choices(LOAN_STATUS, weights=[60,8,4,3,5,4,10,4,2], k=1)[0]
    credit_score = random.randint(580, 850)
    dti = round(random.uniform(15, 55), 2)
    months_delinquent = 0 if loan_status == "Current" else random.randint(1, 24)
    last_payment_date = rand_date(2024, 2026).isoformat()
    next_payment_date = rand_date(2026, 2026).isoformat()
    escrow_balance = round(random.uniform(500, 15000), 2)
    tax_amount_annual = round(random.uniform(1200, 18000), 2)
    insurance_annual = round(random.uniform(600, 5000), 2)
    has_pmi = ltv > 80
    pmi_monthly = round(random.uniform(50, 350), 2) if has_pmi else 0.0
    pmi_provider = random.choice(PMI_PROVIDER[:-1]) if has_pmi else "None"
    coborrower = random.random() < 0.35
    coborrower_first = random.choice(FIRST_NAMES) if coborrower else ""
    coborrower_last = last if coborrower else ""
    coborrower_credit = random.randint(580, 850) if coborrower else None
    coborrower_income = round(random.uniform(25000, 180000), 2) if coborrower else None
    borrower_income = round(random.uniform(35000, 350000), 2)
    flood = random.choice(FLOOD_ZONE)
    flood_ins_required = flood in ("A","AE","V","VE")
    flood_ins_annual = round(random.uniform(400, 3500), 2) if flood_ins_required else 0.0

    row = {
        "loan_id": f"LN{100000 + i}",
        "loan_number": rand_account(),
        "servicer_name": random.choice(SERVICER),
        "investor_name": random.choice(INVESTOR),
        "loan_type": random.choice(LOAN_TYPES),
        "loan_purpose": random.choice(LOAN_PURPOSE),
        "loan_status": loan_status,
        "origination_date": origination_date.isoformat(),
        "maturity_date": maturity_date.isoformat(),
        "original_loan_amount": loan_amount,
        "current_upb": upb,
        "interest_rate": interest_rate,
        "rate_type": rate_type,
        "arm_margin": margin if margin else "",
        "arm_rate_cap": rate_cap if rate_cap else "",
        "arm_rate_floor": rate_floor if rate_floor else "",
        "next_rate_adjustment_date": next_rate_adj,
        "original_loan_term_months": random.choice([180, 240, 360]),
        "remaining_term_months": random.randint(60, 360),
        "pi_payment": pi_payment,
        "escrow_payment_monthly": escrow_monthly,
        "total_monthly_payment": total_payment,
        "payment_frequency": random.choice(PAYMENT_FREQ),
        "escrow_status": random.choice(ESCROW_STATUS),
        "escrow_balance": escrow_balance,
        "last_payment_date": last_payment_date,
        "next_payment_due_date": next_payment_date,
        "months_delinquent": months_delinquent,
        "ltv_ratio": ltv,
        "cltv_ratio": round(ltv + random.uniform(0, 10), 2),
        "appraised_value": appraised_value,
        "property_type": random.choice(PROPERTY_TYPES),
        "occupancy_type": random.choice(OCCUPANCY),
        "property_address_line1": f"{random.randint(100,9999)} {random.choice(['Oak','Main','Elm','Maple','Cedar','Pine','Lake','Park','River','Hill'])} {random.choice(['St','Ave','Blvd','Dr','Ln','Ct','Way','Pl'])}",
        "property_city": city,
        "property_state": state,
        "property_zip": zipcode,
        "property_county": f"{city} County",
        "number_of_units": random.choice([1,1,1,1,2,3,4]),
        "year_built": random.randint(1950, 2024),
        "tax_amount_annual": tax_amount_annual,
        "insurance_type": random.choice(INSURANCE_TYPES),
        "insurance_premium_annual": insurance_annual,
        "flood_zone": flood,
        "flood_insurance_required": flood_ins_required,
        "flood_insurance_annual": flood_ins_annual,
        "has_pmi": has_pmi,
        "pmi_monthly_premium": pmi_monthly,
        "pmi_provider": pmi_provider,
        "origination_channel": random.choice(CHANNEL),
        "documentation_type": random.choice(DOC_TYPE),
        "borrower_first_name": first,
        "borrower_last_name": last,
        "borrower_ssn_masked": f"XXX-XX-{random.randint(1000,9999)}",
        "borrower_dob": dob.isoformat(),
        "borrower_email": rand_email(first, last),
        "borrower_phone": rand_phone(),
        "borrower_marital_status": random.choice(MARITAL),
        "borrower_employment_status": random.choice(EMPLOYMENT),
        "borrower_annual_income": borrower_income,
        "borrower_credit_score": credit_score,
        "borrower_dti_ratio": dti,
        "borrower_bankruptcy_history": random.choice(BANKRUPTCY),
        "coborrower_first_name": coborrower_first,
        "coborrower_last_name": coborrower_last,
        "coborrower_relationship": random.choice(COBORROWER_REL[1:]) if coborrower else "None",
        "coborrower_credit_score": coborrower_credit if coborrower_credit else "",
        "coborrower_annual_income": coborrower_income if coborrower_income else "",
        "modification_type": random.choice(MODIFICATION_TYPE),
        "modification_date": rand_date(2020, 2025).isoformat() if random.random() < 0.15 else "",
        "late_charges_due": round(random.uniform(0, 500), 2) if months_delinquent > 0 else 0.0,
        "suspense_balance": round(random.uniform(0, 2000), 2) if random.random() < 0.08 else 0.0,
        "partial_payment_balance": round(random.uniform(0, 1500), 2) if random.random() < 0.06 else 0.0,
        "last_inspection_date": rand_date(2023, 2026).isoformat() if random.random() < 0.3 else "",
        "mers_registered": random.choice([True, False]),
        "mers_min_number": ''.join(random.choices(string.digits, k=18)) if random.random() < 0.7 else "",
        "investor_loan_id": f"INV-{''.join(random.choices(string.ascii_uppercase + string.digits, k=8))}",
        "boarding_date": (origination_date + timedelta(days=random.randint(30, 120))).isoformat(),
    }
    rows.append(row)

with open(OUT, "w", newline="") as f:
    writer = csv.DictWriter(f, fieldnames=rows[0].keys())
    writer.writeheader()
    writer.writerows(rows)

print(f"Generated {len(rows)} records with {len(rows[0])} fields -> {OUT}")
