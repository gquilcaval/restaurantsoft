import { Component, computed, inject, OnInit, output, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { initials } from './attendance.models';
import { PersonSheetComponent } from './person-sheet.component';
import { entryLabel, postLabel, postOf, StaffPerson } from './team.models';

/** Lista del equipo: quién trabaja en el local y, al tocar a alguien, su ficha. */
@Component({
  selector: 'app-team',
  imports: [IconComponent, PersonSheetComponent],
  templateUrl: './team.component.html',
  styleUrl: './team.component.scss',
})
export class TeamComponent implements OnInit {
  private readonly api = inject(ApiService);
  /** Avisa cuántas personas activas hay (para el contador de la pestaña). */
  readonly counted = output<number>();

  readonly people = signal<StaffPerson[] | null>(null);
  readonly failed = signal(false);
  readonly query = signal('');
  readonly showFormer = signal(false);
  /** Ficha abierta: una persona, 'new' para registrar, o nada. */
  readonly open = signal<StaffPerson | 'new' | null>(null);
  readonly initials = initials;
  readonly postLabel = postLabel;
  readonly entryLabel = entryLabel;

  private readonly filtered = computed(() => {
    const text = this.query().trim().toLowerCase();
    return (this.people() || []).filter((person) => !text
      || [person.fullName, person.documentNumber, person.jobTitle, postLabel(person)].some((value) => (value || '').toLowerCase().includes(text)));
  });
  readonly current = computed(() => this.filtered().filter((person) => person.active));
  readonly former = computed(() => this.filtered().filter((person) => !person.active));
  readonly total = computed(() => (this.people() || []).filter((person) => person.active).length);
  readonly selected = computed(() => { const open = this.open(); return open && open !== 'new' ? open : null; });

  ngOnInit(): void { this.load(); }

  load(): void {
    this.api.get<StaffPerson[]>('/admin/operations/users').subscribe({
      next: (value) => { this.people.set(value); this.failed.set(false); this.counted.emit(this.total()); },
      error: () => this.failed.set(true),
    });
  }

  tone(person: StaffPerson): string { return postOf(person.role).tone; }
}
