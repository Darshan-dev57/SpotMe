// Discover Button from ObsidianUI (https://www.obsidianui.dev), added through its shadcn registry.
// SpotMe changes: optional icon and disabled props.
"use client";

import { ArrowRight } from "lucide-react";
import type { MouseEventHandler, ReactNode } from "react";
import { cn } from "@/lib/utils";
import "./discover-button.css";

export interface DiscoverButtonProps {
  label?: string;
  href?: string;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  className?: string;
  /** SpotMe: lets the caller show a spinner instead of the arrow while busy */
  icon?: ReactNode;
  disabled?: boolean;
}

export function DiscoverButton({
  label = "Discover Components",
  href,
  onClick,
  className,
  icon,
  disabled,
}: DiscoverButtonProps) {
  const buttonFace = (
    <>
      <span className="obsidian-discover-button__fill" aria-hidden="true" />
      <span className="obsidian-discover-button__icon" aria-hidden="true">
        {icon ?? <ArrowRight size={25} strokeWidth={2.25} />}
      </span>
      <span className="obsidian-discover-button__text">{label}</span>
    </>
  );

  if (href) {
    return (
      <a className={cn("obsidian-discover-button", className)} href={href}>
        {buttonFace}
      </a>
    );
  }

  return (
    <button className={cn("obsidian-discover-button", className)} type="button" onClick={onClick} disabled={disabled}>
      {buttonFace}
    </button>
  );
}

export default DiscoverButton;
