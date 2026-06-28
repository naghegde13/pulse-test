# Email Draft: PULSE GCP Service Account Provisioning

**To:** Vikram [+ Capgemini team]
**CC:** [PULSE team]
**Subject:** PULSE Environment Handoff — Service Account Provisioning Script

---

Hi Vikram,

As discussed, we need service account credentials for the CG Dev 1 and CG Dev 2 GCP projects to deploy PULSE. I've attached a script that will create the service accounts and generate the JSON key files for both environments.

## What the script does

1. **Installs Google Cloud CLI** if it's not already on your machine (no admin rights needed -- it installs to your user profile)
2. **Opens a browser window** for you to authenticate with your GCP account
3. **Verifies your permissions** on each project before making any changes
4. **Creates a service account** (`pulse-handoff-sa`) on each project with Owner role
5. **Generates JSON key files** for each service account
6. **Validates the keys** by testing authentication with them
7. **Prints a summary** showing success/failure for each project

The script handles both CG Dev 1 and CG Dev 2 in a single run. It will prompt you for each project's GCP Project ID.

## Prerequisites

- **Windows 10 or later** (the script runs on Windows)
- **Internet access** (to download the GCP CLI if needed, and to communicate with GCP APIs)
- **A web browser** (Chrome, Edge, etc. -- for GCP authentication)
- **Admin/Owner access** to both CG Dev 1 and CG Dev 2 GCP projects
- **Your GCP email address** (the one linked to your admin access)
- **The GCP Project IDs** for both CG Dev 1 and CG Dev 2 (found at https://console.cloud.google.com → select project → Dashboard → Project ID)

## How to run

1. **Download** the attached `cg-provision-sa.zip` file
2. **Extract** the zip file (right-click → "Extract All...")
3. **Rename** the extracted file from `cg-provision-sa.txt` to `cg-provision-sa.bat`
4. **Double-click** `cg-provision-sa.bat` to run it
5. **Follow the prompts:**
   - Enter your GCP email address
   - Enter the GCP Project ID for CG Dev 1
   - Enter the GCP Project ID for CG Dev 2
   - A browser window will open for authentication -- sign in and grant permissions
   - The script will process both projects automatically

## Output

When complete, the script creates a `sa-keys` folder next to the script file containing:
- `<project-id-1>-sa-key.json` -- Service account key for CG Dev 1
- `<project-id-2>-sa-key.json` -- Service account key for CG Dev 2

## Sending us the key files

**Important:** Please do NOT send the JSON key files via email. They contain sensitive credentials. Instead, please share them via one of:
- **OneDrive/SharePoint** shared link (with expiration)
- **Password-protected zip** (share password separately via Teams/chat)

## Troubleshooting

- **"Insufficient Permissions" error:** Your account does not have admin access to the project. Ask your GCP org admin to grant you `roles/owner` on the project.
- **"Org policy blocks key creation" error:** Your organization enforces a policy that prevents service account key creation. The script will try to override it automatically, but if it can't, you'll need your GCP org admin to disable the `iam.disableServiceAccountKeyCreation` constraint on the project.
- **"Cannot access project" error:** Double-check the Project ID (not the Project Name). You can find it on the GCP Console dashboard.
- **The script is safe to re-run** -- it detects already-created resources and skips them.

Let me know if you run into any issues.

Thanks,
[Your name]
