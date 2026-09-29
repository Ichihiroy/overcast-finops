terraform {
  required_version = ">= 1.9"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.7"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.17"
    }
  }

  # Remote state in Azure Storage (created by infra/bootstrap/bootstrap.sh).
  # All values injected at init time via -backend-config; nothing hardcoded here.
  backend "azurerm" {}
}
